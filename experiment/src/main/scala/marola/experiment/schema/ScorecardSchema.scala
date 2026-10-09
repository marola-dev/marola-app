package marola.experiment.schema

import java.nio.file.{Files, Path}

import kyo.*
import kyo.Json.JsonSchema

/**
 * The published scorecard's JSON Schema, checked in as `experiment/scorecard.schema.json` so a
 * consumer can pin it. `sbt "experiment/runMain marola.experiment.schema.ScorecardSchema <path>"`
 * regenerates it; SchemaSpec fails when the checked-in copy drifts.
 */
object ScorecardSchema:

  // Trap: Json.jsonSchema ignores renameAllFields (kyo 1.0.0-RC7) and emits the Scala names,
  // so the property names are snake-cased here to match what Json.encode writes.
  def schema(using Frame): JsonSchema = snakeKeys(Json.jsonSchema[Scorecard])

  def json(using Frame): String = Json.encode(schema)

  def snakeCase(name: String): String =
    name.flatMap(c => if c.isUpper then s"_${c.toLower}" else c.toString)

  private def snakeKeys(s: JsonSchema): JsonSchema = s match
    case o: JsonSchema.Obj =>
      o.copy(
        properties = o.properties.map((k, v) => snakeCase(k) -> snakeKeys(v)),
        required = o.required.map(snakeCase)
      )
    case a: JsonSchema.Arr      => a.copy(items = snakeKeys(a.items))
    case n: JsonSchema.Nullable => n.copy(inner = snakeKeys(n.inner))
    case v: JsonSchema.OneOf    => v.copy(variants = v.variants.map((k, x) => k -> snakeKeys(x)))
    case other                  => other

  def main(args: Array[String]): Unit =
    val _ = Files.writeString(Path.of(args(0)), json + "\n")
end ScorecardSchema
