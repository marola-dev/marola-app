package marola.oods

/**
 * The Open Ocean Data Store's command-line entry point (MIP-0075 §5.4), a second main class in the
 * same jar as `marola.Main`: `java -cp marola.jar marola.oods.Main <command> …`. No command is
 * implemented yet; each lands with its own MIP-0075 task.
 */
object Main:

  /** The usage-or-missing-setting exit code; `usage` lists all four. */
  val Usage = 2

  val Commands: List[String] = List("beaches", "load", "maintain", "export", "check", "status")

  val usage: String =
    """usage:
      |  oods beaches --areas FILE [--area ID]… [--dry-run]
      |  oods load (--state UF | --source ID)… --sources FILE [--water-positions FILE] [--mode incremental|backfill] [--from-year Y] [--to-year Y] [--max-minutes M] [--dry-run]
      |  oods maintain [--keep-days 30]
      |  oods export [--water-positions FILE]
      |  oods check
      |  oods status [--area ID | --state UF]
      |
      |exit codes:
      |  0  every job ended without `failed`
      |  1  at least one job `failed`
      |  2  usage, or a missing setting
      |  3  `oods check` found a violation, or the catalog will not open
      |
      |settings (env): OODS_S3_KEY_ID, OODS_S3_SECRET, OODS_S3_ENDPOINT, OODS_S3_REGION,
      |  OODS_S3_URL_STYLE, OODS_S3_USE_SSL, OODS_BUCKET, OODS_CATALOG, OODS_KEY_NAME, MAROLA_BR_PROXY
      |""".stripMargin

  /** The exit code and what goes to stderr; `main` is the only side effect. */
  def run(args: Seq[String]): (Int, String) =
    args.headOption match
      case None                                => (Usage, usage)
      case Some(cmd) if Commands.contains(cmd) => (Usage, s"oods $cmd: not implemented yet\n")
      case Some(cmd)                           => (Usage, s"oods: unknown command '$cmd'\n$usage")

  def main(args: Array[String]): Unit =
    val (code, stderr) = run(args.toSeq)
    System.err.print(stderr)
    sys.exit(code)
