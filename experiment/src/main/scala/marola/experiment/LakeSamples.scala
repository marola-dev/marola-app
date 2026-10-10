package marola.experiment

import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import java.util.Properties

import scala.io.Source
import scala.util.Using

import kyo.*
import kyo.Structure.Value as V

import marola.experiment.schema.*

import org.duckdb.DuckDBDriver

/** `SampleStore` over DuckDB, the lake attached as `lake` (MIP-0083 §5.6). */
final class LakeSamples private (conn: Connection, pageSize: Int) extends SampleStore:
  import LakeSamples.*

  def writePoints(points: Chunk[SamplingPoint]): Unit < Sync = upsert(PointTable, points)
  def writeForecasts(samples: Chunk[ForecastSample]): Unit < Sync = upsert(ForecastTable, samples)
  def writeObservations(samples: Chunk[ObservationSample]): Unit < Sync =
    upsert(ObservationTable, samples)
  def writeRuns(rows: Chunk[RunIndexRow]): Unit < Sync = upsert(RunTable, rows)
  def writeScoreCells(cells: Chunk[ScoreCell]): Unit < Sync = upsert(ScoreTable, cells)
  def writePairCells(cells: Chunk[PairCell]): Unit < Sync = upsert(PairTable, cells)

  def points: Chunk[SamplingPoint] < Sync = paged(PointTable, "TRUE", Chunk.empty).run
  def forecasts(from: Instant, until: Instant): Stream[ForecastSample, Sync] =
    paged(ForecastTable, between("valid_time"), Chunk(utc(from), utc(until)))
  def observations(from: Instant, until: Instant): Stream[ObservationSample, Sync] =
    paged(ObservationTable, between("valid_time"), Chunk(utc(from), utc(until)))
  def runs(from: Instant, until: Instant): Stream[RunIndexRow, Sync] =
    paged(RunTable, between("run_init"), Chunk(utc(from), utc(until)))
  def scoreCells(from: LocalDate, until: LocalDate): Stream[ScoreCell, Sync] =
    paged(ScoreTable, between("day"), Chunk(from, until))
  def pairCells(from: LocalDate, until: LocalDate): Stream[PairCell, Sync] =
    paged(PairTable, between("day"), Chunk(from, until))

  // Staged outside the transaction: DuckDB lets one transaction write to one attached database.
  private def upsert[A](t: Table[A], rows: Chunk[A]): Unit < Sync =
    Sync.defer {
      if rows.nonEmpty then
        val stage = s"stage_${t.name}"
        exec(s"CREATE OR REPLACE TEMP TABLE $stage AS SELECT * FROM lake.${t.name} LIMIT 0")
        try
          val columns = columnsOf(stage)
          val ps =
            conn.prepareStatement(
              s"INSERT INTO $stage VALUES (${columns.map(_ => "?").mkString(", ")})"
            )
          try
            rows.foreach { a =>
              // An encoded record leaves a None out.
              val present = fields(Structure.encode(a)(using t.schema)).toMap
              columns.zipWithIndex.foreach((c, i) =>
                ps.setObject(i + 1, toJdbc(present.getOrElse(c, V.Null)))
              )
              ps.addBatch()
            }
            val _ = ps.executeBatch()
          finally ps.close()
          val same =
            t.key.map(k => s"s.$k IS NOT DISTINCT FROM lake.${t.name}.$k").mkString(" AND ")
          conn.setAutoCommit(false)
          var committed = false
          try
            exec(s"DELETE FROM lake.${t.name} WHERE EXISTS (SELECT 1 FROM $stage s WHERE $same)")
            exec(
              s"INSERT INTO lake.${t.name} SELECT DISTINCT ON (${t.key.mkString(", ")}) * FROM $stage"
            )
            conn.commit()
            committed = true
          finally
            if !committed then conn.rollback()
            conn.setAutoCommit(true)
        finally exec(s"DROP TABLE IF EXISTS $stage")
    }

  // Keyset paging on `t.order`, a total order, so a page never re-reads the window before it.
  private def paged[A](t: Table[A], where: String, params: Chunk[AnyRef])(using
      Tag[Emit[Chunk[A]]]
  ): Stream[A, Sync] =
    val order = t.order.mkString(", ")
    val base = s"SELECT *, $order FROM lake.${t.name} WHERE $where"
    Stream[A, Sync] {
      Loop(Chunk.empty[AnyRef]) { after =>
        Sync.defer(page(t, base, order, params, after)).map { (rows, last) =>
          if rows.isEmpty then Loop.done
          else if rows.size < pageSize then Emit.valueWith(rows)(Loop.done)
          else Emit.valueWith(rows)(Loop.continue(last))
        }
      }
    }

  private def page[A](
      t: Table[A],
      base: String,
      order: String,
      params: Chunk[AnyRef],
      after: Chunk[AnyRef]
  ): (Chunk[A], Chunk[AnyRef]) =
    val seek =
      if after.isEmpty then ""
      else s" AND ($order) > (${Seq.fill(after.size)("?").mkString(", ")})"
    val ps = conn.prepareStatement(s"$base$seek ORDER BY $order LIMIT $pageSize")
    try
      (params ++ after).zipWithIndex.foreach((v, i) => ps.setObject(i + 1, v))
      val rs = ps.executeQuery()
      try
        val meta = rs.getMetaData
        val n = meta.getColumnCount - t.order.size
        val columns = Chunk.from((1 to n).map(meta.getColumnName))
        val out = Chunk.newBuilder[A]
        var last = Chunk.empty[AnyRef]
        while rs.next() do
          val record =
            V.Record(columns.zipWithIndex.map((c, i) => c -> fromJdbc(rs.getObject(i + 1))))
          out += Structure.decode(record)(using t.schema).getOrThrow
          last = Chunk.from((n + 1 to n + t.order.size).map(i => rs.getObject(i)))
        (out.result(), last)
      finally rs.close()
    finally ps.close()

  private def columnsOf(table: String): Chunk[String] =
    val ps = conn.prepareStatement(s"SELECT * FROM $table LIMIT 0")
    try
      val meta = ps.getMetaData
      Chunk.from((1 to meta.getColumnCount).map(meta.getColumnName))
    finally ps.close()

  private def exec(sql: String): Unit =
    val st = conn.createStatement()
    try
      val _ = st.execute(sql)
    finally st.close()

end LakeSamples

object LakeSamples:

  def open(catalog: LakeCatalog, pageSize: Int): SampleStore < (Scope & Sync) =
    Scope
      .acquire(Sync.defer(connect()))
      .map { conn =>
        Sync.defer {
          attach(conn, catalog)
          new LakeSamples(conn, pageSize)
        }
      }

  // An in-memory DuckDB the lake is attached to. The driver directly, not DriverManager: its
  // ServiceLoader lookup misses the driver under sbt's layered class loaders.
  private[experiment] def connect(): Connection =
    DuckDBDriver().connect("jdbc:duckdb:", Properties())

  private def attach(conn: Connection, catalog: LakeCatalog): Unit =
    val sql = catalog match
      case LakeCatalog.DuckLake(dir) =>
        val _ = Files.createDirectories(dir.resolve("data"))
        Seq(
          "INSTALL ducklake",
          "LOAD ducklake",
          s"ATTACH 'ducklake:${quote(dir.resolve("lake.ducklake"))}' AS lake " +
            s"(DATA_PATH '${quote(dir.resolve("data"))}')"
        )
      case LakeCatalog.DuckDbFile(file) =>
        Option(file.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
        Seq(s"ATTACH '${quote(file)}' AS lake")
    val st = conn.createStatement()
    try (sql :+ ddl).foreach(st.execute(_))
    finally st.close()

  private def quote(p: Path): String = p.toAbsolutePath.toString.replace("'", "''")

  // lake.sql is the schema's only copy (SchemaSpec checks it); made re-runnable here.
  private lazy val ddl: String =
    Using
      .resource(Source.fromResource("experiment/lake.sql", getClass.getClassLoader))(_.mkString)
      .replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS lake.")

  private def utc(t: Instant): OffsetDateTime = OffsetDateTime.ofInstant(t, ZoneOffset.UTC)

  private def between(column: String): String = s"$column >= ? AND $column < ?"

  private def fields(v: Structure.Value): Chunk[(String, Structure.Value)] = v match
    case V.Record(fs) => fs
    case other        => throw IllegalArgumentException(s"not a lake row: $other")

  private def toJdbc(v: Structure.Value): AnyRef = v match
    case V.Str(s)     => s
    case V.Integer(n) => Long.box(n)
    case V.Decimal(d) => Double.box(d)
    case V.Bool(b)    => Boolean.box(b)
    case V.Instant(t) => utc(t)
    case V.Null       => null
    case other        => throw IllegalArgumentException(s"no lake column type for $other")

  // kyo-schema reads a LocalDate from its ISO string.
  private def fromJdbc(v: AnyRef): Structure.Value = v match
    case null                 => V.Null
    case s: String            => V.Str(s)
    case b: java.lang.Boolean => V.Bool(b)
    case n: java.lang.Integer => V.Integer(n.toLong)
    case n: java.lang.Long    => V.Integer(n)
    case d: java.lang.Double  => V.Decimal(d)
    case t: OffsetDateTime    => V.Instant(t.toInstant)
    case d: LocalDate         => V.Str(d.toString)
    case other                => throw IllegalArgumentException(s"no field type for $other")

  /**
   * One lake table, its rows read and written through the record's kyo-schema (column = wire
   * field). `key` is the natural key a write replaces on, `order` a total order over it for paging.
   */
  final private case class Table[A](name: String, key: Chunk[String], order: Chunk[String])(using
      val schema: Schema[A]
  )

  private val PointTable = Table[SamplingPoint]("experiment_point", Chunk("id"), Chunk("id"))

  private val ForecastTable = Table[ForecastSample](
    "forecast_sample",
    Chunk("provider", "run_init", "point", "lead_h", "variable", "member"),
    Chunk(
      "valid_time",
      "provider",
      "run_init",
      "point",
      "lead_h",
      "variable",
      "coalesce(member, -1)"
    )
  )

  private val ObservationTable = Table[ObservationSample](
    "observation_sample",
    Chunk("instrument", "valid_time", "variable"),
    Chunk("valid_time", "instrument", "variable")
  )

  private val RunTable =
    Table[RunIndexRow]("run_index", Chunk("provider", "run_init"), Chunk("run_init", "provider"))

  private val ScoreTable = Table[ScoreCell](
    "score_cell",
    Chunk("provider", "point", "variable", "lead_h", "bin", "day"),
    Chunk("day", "provider", "point", "variable", "lead_h", "bin")
  )

  private val PairTable = Table[PairCell](
    "pair_cell",
    Chunk("provider_a", "provider_b", "point", "variable", "lead_h", "day"),
    Chunk("day", "provider_a", "provider_b", "point", "variable", "lead_h")
  )

end LakeSamples
