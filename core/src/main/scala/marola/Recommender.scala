package marola

import kyo.*
import marola.beaches.BeachFinder
import marola.conditions.OpenMeteoClient
import marola.model.*
import marola.scoring.Swimability
import java.time.{LocalDate, ZoneId}

/**
 * Answers "what's the best hour tomorrow to swim nearby?": find beaches near `origin`, fetch each
 * one's forecast, score every hour that falls on *tomorrow in that beach's own local timezone*, and
 * return every (beach, hour) ranked best-first.
 *
 * This is the whole POC pipeline. It's built as a plain function returning `List[BestHour] < Sync`
 * rather than a Foundry-agent-driven flow: there is no LLM call in the critical path — that's a
 * separate step `Main` (in `marola-cli`) composes afterward. See `docs/ARCHITECTURE.md` for where
 * the LLM synthesis step and the DSPy-optimized prompt behind it plug in.
 *
 * `distanceRefiner` is dependency-inverted rather than a hardcoded Azure Maps call: `Recommender`
 * lives in `marola-core`, which has zero Azure SDK dependency by design (`FUTURE-WORK.md` §7.2) —
 * `marola-azure`'s `RouteFinder` depends on `marola-core`, not the other way around, so
 * `Recommender` can't reference it directly. `marola-cli` (which depends on both) supplies the
 * actual `RouteFinder.travelDistanceKm` function when an Azure Maps key is configured; `None` (the
 * default) keeps every beach's haversine distance as-is.
 */
object Recommender:

  def bestHoursTomorrow(
      origin: Coordinates,
      radiusKm: Double = 15.0,
      beachLimit: Int = 6,
      distanceRefiner: Option[(Coordinates, Coordinates) => Double < Sync] = None
  ): List[BestHour] < Sync =
    for
      beaches <- BeachFinder.nearby(origin, radiusKm, beachLimit)
      refined <- refineDistances(origin, beaches, distanceRefiner)
      scored <- traverse(refined)(scoreTomorrow)
    yield scored.flatten.sortBy(-_.score)

  /**
   * Upgrades each beach's haversine distance (`BeachFinder`'s "as the crow flies" default) to
   * whatever `distanceRefiner` computes — a no-op when `None`, which is the entire local-vs-Azure
   * toggle for this feature. Applied to the already radius-filtered short list, not every Overpass
   * hit, to keep call volume to a real routing API bounded. A per-beach refiner failure (bad key,
   * rate limit, network) falls back to that beach's existing haversine distance rather than failing
   * the whole recommendation.
   */
  private def refineDistances(
      origin: Coordinates,
      beaches: List[Beach],
      distanceRefiner: Option[(Coordinates, Coordinates) => Double < Sync]
  ): List[Beach] < Sync =
    distanceRefiner match
      case None => beaches
      case Some(refine) =>
        traverseSingle(beaches) { beach =>
          Abort
            .run(Abort.catching[Throwable](refine(origin, beach.coordinates)))
            .map {
              case Result.Success(km) => beach.copy(distanceKm = km)
              case _                  => beach
            }
        }

  /**
   * One row per nearby beach (its single best hour tomorrow), ranked best-first — this is the
   * "conditions for each of them" view `Main` prints. `bestHoursTomorrow` returns every (beach,
   * hour) pair instead, which is dominated by whichever one beach happens to have the best
   * conditions across all 24 hours.
   */
  def bestPerBeachTomorrow(
      origin: Coordinates,
      radiusKm: Double = 15.0,
      beachLimit: Int = 6,
      distanceRefiner: Option[(Coordinates, Coordinates) => Double < Sync] = None
  ): List[BestHour] < Sync =
    bestHoursTomorrow(origin, radiusKm, beachLimit, distanceRefiner).map { all =>
      all
        .groupBy(_.beach.name)
        .values
        .map(_.maxBy(_.score))
        .toList
        .sortBy(-_.score)
    }

  private def scoreTomorrow(beach: Beach): List[BestHour] < Sync =
    OpenMeteoClient.forecastFor(beach).map { forecast =>
      val tomorrow = LocalDate.now(ZoneId.of(forecast.timezoneId)).plusDays(1)
      forecast.hours
        .filter(_.time.toLocalDate.isEqual(tomorrow))
        .map { hour =>
          val (score, notes) = Swimability.score(hour)
          BestHour(
            beach,
            hour,
            score,
            Swimability.jellyfishRisk(hour),
            Swimability.whaleSightingLikelihood(hour),
            notes
          )
        }
    }

  /**
   * Small hand-rolled effectful traverse — kept local rather than reaching for a kyo-combinators
   * method because this module's dependency policy (see build.sbt/Http.scala) is to only rely on
   * Kyo APIs actually confirmed against the pinned 1.0.0-RC5 build; `map`/`flatMap` on `< Sync` are
   * confirmed (Http.scala, OpenMeteoClient.scala), so this is built from those alone.
   */
  private def traverse[A, B](items: List[A])(f: A => List[B] < Sync): List[List[B]] < Sync =
    items match
      case Nil => Nil
      case head :: tail =>
        for
          b <- f(head)
          bs <- traverse(tail)(f)
        yield b :: bs

  /**
   * Same rationale as `traverse` above, for the common one-in-one-out shape (`refineDistances`).
   */
  private def traverseSingle[A, B](items: List[A])(f: A => B < Sync): List[B] < Sync =
    items match
      case Nil => Nil
      case head :: tail =>
        for
          b <- f(head)
          bs <- traverseSingle(tail)(f)
        yield b :: bs
