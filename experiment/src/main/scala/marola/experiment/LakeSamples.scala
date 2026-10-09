package marola.experiment

import java.nio.file.{Files, Path}
import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import java.util.Properties

import scala.io.Source
import scala.util.control.NoStackTrace

import kyo.*

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
          val marks = Seq.fill(t.columns)("?").mkString(", ")
          val ps = conn.prepareStatement(s"INSERT INTO $stage VALUES ($marks)")
          try
            rows.foreach { a =>
              t.write(Out(ps), a)
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
        val out = Chunk.newBuilder[A]
        var last = Chunk.empty[AnyRef]
        while rs.next() do
          out += t.read(In(rs))
          last = Chunk.from((t.columns + 1 to t.columns + t.order.size).map(i => rs.getObject(i)))
        (out.result(), last)
      finally rs.close()
    finally ps.close()

  private def exec(sql: String): Unit =
    val st = conn.createStatement()
    try
      val _ = st.execute(sql)
    finally st.close()

end LakeSamples

object LakeSamples:

  /** A row the lake holds that the schema cannot read back: a hand edit or a newer protocol. */
  final class CorruptRow(msg: String) extends Exception(msg) with NoStackTrace

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
    try (sql ++ ddl).foreach(st.execute(_))
    finally st.close()

  private def quote(p: Path): String = p.toAbsolutePath.toString.replace("'", "''")

  // lake.sql is the schema's only copy (SchemaSpec checks it); made re-runnable here.
  private lazy val ddl: Seq[String] =
    val src = Source.fromResource("experiment/lake.sql", getClass.getClassLoader)
    val text =
      try src.getLines().filterNot(_.trim.startsWith("--")).mkString("\n")
      finally src.close()
    text
      .split(';')
      .toSeq
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.replaceFirst("CREATE TABLE (\\w+)", "CREATE TABLE IF NOT EXISTS lake.$1"))

  private def utc(t: Instant): OffsetDateTime = OffsetDateTime.ofInstant(t, ZoneOffset.UTC)

  private def between(column: String): String = s"$column >= ? AND $column < ?"

  /**
   * One lake table: `write` and `read` follow lake.sql's column order, `key` is the natural key a
   * write replaces on, and `order` is a total order over it for paging.
   */
  final private case class Table[A](
      name: String,
      columns: Int,
      key: Chunk[String],
      order: Chunk[String],
      write: (Out, A) => Unit,
      read: In => A
  )

  final private class Out(ps: PreparedStatement):
    private var i = 0
    private def next(): Int =
      i += 1
      i
    def str(v: String): Unit = ps.setString(next(), v)
    def strOpt(v: Option[String]): Unit = ps.setObject(next(), v.orNull)
    def int(v: Int): Unit = ps.setInt(next(), v)
    def intOpt(v: Option[Int]): Unit = ps.setObject(next(), v.map(Int.box).orNull)
    def long(v: Long): Unit = ps.setLong(next(), v)
    def dbl(v: Double): Unit = ps.setDouble(next(), v)
    def bool(v: Boolean): Unit = ps.setBoolean(next(), v)
    def ts(v: Instant): Unit = ps.setObject(next(), utc(v))
    def tsOpt(v: Option[Instant]): Unit = ps.setObject(next(), v.map(utc).orNull)
    def date(v: LocalDate): Unit = ps.setObject(next(), v)
    def dateOpt(v: Option[LocalDate]): Unit = ps.setObject(next(), v.orNull)
    def label(v: Labeled): Unit = str(v.label)

  // Constructor arguments evaluate left to right, so `Record(in.str, in.ts, …)` reads in order.
  final private class In(rs: ResultSet):
    private var i = 0
    private def next(): Int =
      i += 1
      i
    def str: String = rs.getString(next())
    def strOpt: Option[String] = Option(rs.getString(next()))
    def int: Int = rs.getInt(next())
    def intOpt: Option[Int] =
      val v = rs.getInt(next())
      if rs.wasNull() then None else Some(v)
    def long: Long = rs.getLong(next())
    def dbl: Double = rs.getDouble(next())
    def bool: Boolean = rs.getBoolean(next())
    def ts: Instant = rs.getObject(next(), classOf[OffsetDateTime]).toInstant
    def tsOpt: Option[Instant] =
      Option(rs.getObject(next(), classOf[OffsetDateTime])).map(_.toInstant)
    def date: LocalDate = rs.getObject(next(), classOf[LocalDate])
    def dateOpt: Option[LocalDate] = Option(rs.getObject(next(), classOf[LocalDate]))
    def label[E <: Labeled](values: Array[E]): E =
      val s = str
      values.find(_.label == s).getOrElse(throw CorruptRow(s"unknown label '$s' in column $i"))

  private val PointTable = Table[SamplingPoint](
    "experiment_point",
    7,
    Chunk("id"),
    Chunk("id"),
    (o, p) => {
      o.str(p.id)
      o.dbl(p.lat)
      o.dbl(p.lon)
      o.label(p.kind)
      o.strOpt(p.instrument)
      o.date(p.addedOn)
      o.dateOpt(p.retiredOn)
    },
    in =>
      SamplingPoint(
        in.str,
        in.dbl,
        in.dbl,
        in.label(PointKind.values),
        in.strOpt,
        in.date,
        in.dateOpt
      )
  )

  private val ForecastTable = Table[ForecastSample](
    "forecast_sample",
    12,
    Chunk("provider", "run_init", "point", "lead_h", "variable", "member"),
    Chunk(
      "valid_time",
      "provider",
      "run_init",
      "point",
      "lead_h",
      "variable",
      "coalesce(member, -1)"
    ),
    (o, s) => {
      o.str(s.provider)
      o.ts(s.runInit)
      o.ts(s.fetchedAt)
      o.str(s.point)
      o.ts(s.validTime)
      o.int(s.leadH)
      o.label(s.variable)
      o.intOpt(s.member)
      o.dbl(s.value)
      o.dbl(s.cellLat)
      o.dbl(s.cellLon)
      o.str(s.sourceUrl)
    },
    in =>
      ForecastSample(
        in.str,
        in.ts,
        in.ts,
        in.str,
        in.ts,
        in.int,
        in.label(Variable.values),
        in.intOpt,
        in.dbl,
        in.dbl,
        in.dbl,
        in.str
      )
  )

  private val ObservationTable = Table[ObservationSample](
    "observation_sample",
    6,
    Chunk("instrument", "valid_time", "variable"),
    Chunk("valid_time", "instrument", "variable"),
    (o, s) => {
      o.str(s.instrument)
      o.ts(s.validTime)
      o.label(s.variable)
      o.dbl(s.value)
      o.str(s.averaging)
      o.bool(s.qcPassed)
    },
    in => ObservationSample(in.str, in.ts, in.label(Variable.values), in.dbl, in.str, in.bool)
  )

  private val RunTable = Table[RunIndexRow](
    "run_index",
    7,
    Chunk("provider", "run_init"),
    Chunk("run_init", "provider"),
    (o, r) => {
      o.str(r.provider)
      o.ts(r.runInit)
      o.tsOpt(r.availableAt)
      o.strOpt(r.contentSha)
      o.label(r.state)
      o.strOpt(r.reason)
      o.tsOpt(r.fetchedAt)
    },
    in =>
      RunIndexRow(
        in.str,
        in.ts,
        in.tsOpt,
        in.strOpt,
        in.label(RunState.values),
        in.strOpt,
        in.tsOpt
      )
  )

  private val ScoreTable = Table[ScoreCell](
    "score_cell",
    12,
    Chunk("provider", "point", "variable", "lead_h", "bin", "day"),
    Chunk("day", "provider", "point", "variable", "lead_h", "bin"),
    (o, c) => {
      o.str(c.provider)
      o.str(c.point)
      o.label(c.variable)
      o.int(c.leadH)
      o.str(c.bin)
      o.date(c.day)
      o.long(c.n)
      o.dbl(c.sumE)
      o.dbl(c.sumSqE)
      o.dbl(c.sumAbsE)
      o.dbl(c.sumCrps)
      o.dbl(c.sumSqSpread)
    },
    in =>
      ScoreCell(
        in.str,
        in.str,
        in.label(Variable.values),
        in.int,
        in.str,
        in.date,
        in.long,
        in.dbl,
        in.dbl,
        in.dbl,
        in.dbl,
        in.dbl
      )
  )

  private val PairTable = Table[PairCell](
    "pair_cell",
    12,
    Chunk("provider_a", "provider_b", "point", "variable", "lead_h", "day"),
    Chunk("day", "provider_a", "provider_b", "point", "variable", "lead_h"),
    (o, c) => {
      o.str(c.providerA)
      o.str(c.providerB)
      o.str(c.point)
      o.label(c.variable)
      o.int(c.leadH)
      o.date(c.day)
      o.long(c.n)
      o.dbl(c.sumD)
      o.dbl(c.sumSqD)
      o.dbl(c.sumEaEb)
      o.dbl(c.sumSqEa)
      o.dbl(c.sumSqEb)
    },
    in =>
      PairCell(
        in.str,
        in.str,
        in.str,
        in.label(Variable.values),
        in.int,
        in.date,
        in.long,
        in.dbl,
        in.dbl,
        in.dbl,
        in.dbl,
        in.dbl
      )
  )

end LakeSamples
