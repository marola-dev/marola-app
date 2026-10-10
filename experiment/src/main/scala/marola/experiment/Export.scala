package marola.experiment

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate, ZoneOffset}

import kyo.*

import marola.experiment.schema.*
import marola.experiment.score.{Monoid, Prediction, Score}
import marola.log.Log

/** MIP-0083 §5.8: the scorecard marola-site reads, written only once a point is `scored`. */
object Export:

  private val log = Log.forName(getClass.getName)

  /** (provider, point, variable, lead, bin) to its sums over the window. */
  type Window = Map[(String, String, Variable, Int, String), Score]

  // Only what is checked: the observation networks' terms are not recorded yet.
  val Licences: Chunk[String] =
    Chunk("Open-Meteo forecasts: CC BY 4.0, https://open-meteo.com/en/licence")

  val SkipReason = "no ground-truth point is scored yet (MIP-0083 §5.8)"

  /** The protocol's window ending on `now`'s day, folded one daily cell at a time. */
  def window(now: Instant)(using Frame): Window < (Sync & Env[Deps]) =
    Env.get[Deps].map { d =>
      val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
      val m = summon[Monoid[Score]]
      d.store
        .scoreCells(today.minusDays(d.protocol.windowDays - 1L), today.plusDays(1))
        .fold(Map.empty: Window)((acc, c) =>
          acc.updatedWith((c.provider, c.point, c.variable, c.leadH, c.bin))(s =>
            Some(m.combine(s.getOrElse(m.empty), Score.of(c)))
          )
        )
    }

  /**
   * By lead (the metric's step): `rmse.<variable>.<window>d.<point>.<provider>`, headline leads.
   */
  def metrics(p: Protocol, w: Window): Map[Int, Map[String, Double]] =
    val leads = p.scorecardDays.map(_ * 24).toSet
    w.toSeq
      .collect {
        case ((provider, point, v, lead, "all"), s) if leads.contains(lead) && s.n > 0 =>
          val suffix = s"${v.label}.${p.windowDays}d.$point.$provider"
          lead -> Map(
            s"rmse.$suffix" -> s.rmse,
            s"bias.$suffix" -> s.bias,
            s"crps.$suffix" -> s.crps
          )
      }
      .groupMapReduce(_._1)(_._2)(_ ++ _)

  /** Rows over the scored points only; `None` until one is scored. */
  def scorecard(
      gt: GroundTruth,
      points: Chunk[SamplingPoint],
      p: Protocol,
      w: Window,
      now: Instant
  ): Option[Scorecard] =
    val scored = gt.scored.map(_.id).toSet
    val ids = points.filter(_.instrument.exists(scored.contains)).map(_.id).toSet
    Option.when(ids.nonEmpty) {
      val rows = for
        provider <- w.keys.map(_._1).toSeq.sorted
        v <- p.variables
        day <- p.scorecardDays
        s = Monoid.fold(w.collect {
          case ((`provider`, point, `v`, lead, "all"), s) if ids(point) && lead == day * 24 => s
        })
        if s.n > 0
      yield ScorecardRow(provider, v, day, s.n, s.bias, s.rmse, s.crps, s.n < p.minN)
      Scorecard(p.version, now, p.windowDays, Chunk.from(rows), Licences)
    }

  val PredictionsFile = "predictions.csv"

  /**
   * One row per forecast; `forecast` is the members' mean, `observed` and `error` blank if
   * unmatched.
   */
  def predictionsCsv(rows: Chunk[Prediction]): String =
    val header =
      "valid_time,point,instrument,variable,provider,run_init,lead_h,forecast,members,observed,error"
    (header +: rows.map { p =>
      val forecast = p.members.sum / p.members.size
      Seq(
        p.validTime,
        p.point,
        p.instrument,
        p.variable.label,
        p.provider,
        p.runInit,
        p.leadH,
        forecast,
        p.members.size,
        p.observed.getOrElse(""),
        p.observed.fold("")(o => (forecast - o).toString)
      ).mkString(",")
    }).mkString("", "\n", "\n")

  /** `export.json` under `out`, or a logged reason and `None`. */
  def write(now: Instant)(using Frame): Option[Path] < (Sync & Env[Deps]) =
    Env.get[Deps].map { d =>
      val card =
        if d.groundTruth.scored.isEmpty then Kyo.lift(Option.empty[Scorecard])
        else window(now).map(scorecard(d.groundTruth, d.points, d.protocol, _, now))
      card.map {
        case None =>
          Sync.defer {
            log.info(s"export skipped: $SkipReason")
            None
          }
        case Some(c) =>
          Sync.defer {
            Files.createDirectories(d.out)
            Some(Files.writeString(d.out.resolve("export.json"), Json.encode(c) + "\n"))
          }
      }
    }
end Export
