package client.cmd

@clip.group(
  "mycli",
  "My CLI application",
  subcommands = Seq(authn.command),
  version = "0.1.0"
)
def mycli(
  logLevel: Int = 3,
  @clip.arg(
    "--log-config",
    help = "Advanced log configuration in the form of logger=level. Can be specified multiple times.",
    repeats = true
  )
  logConfig: Seq[(String, String)] = Seq.empty,
) =
  val minimumLevel = logLevel match
    case 0 => None
    case 1 => Some(scribe.Level.Error)
    case 2 => Some(scribe.Level.Warn)
    case 3 => Some(scribe.Level.Info)
    case 4 => Some(scribe.Level.Debug)
    case 5 => Some(scribe.Level.Trace)
    case _ => clip.abort("Invalid log level. Must be between 0 and 5")

  if minimumLevel.isEmpty then
    scribe.Logger.root.clearHandlers().replace()
  else
    scribe.Logger.root
      .clearHandlers()
      .withHandler(
        formatter = scribe.format.Formatter.compact
      )
      .withMinimumLevel(minimumLevel.get)
      .replace()

  for (logger, level) <- logConfig do
    val parsedLevel = level.toLowerCase match
      case "error" => scribe.Level.Error
      case "warn" => scribe.Level.Warn
      case "info" => scribe.Level.Info
      case "debug" => scribe.Level.Debug
      case "trace" => scribe.Level.Trace
      case _ => clip.abort(s"Invalid log level '$level' for logger '$logger'. Must be one of error, warn, info, debug, trace.")
    scribe.Logger(logger).withMinimumLevel(parsedLevel).replace()

def main(args: Array[String]): Unit =
  val err = System.err
  val newErr = new java.io.PrintStream(new java.io.OutputStream {
    val buffer = new StringBuilder
    def write(b: Int): Unit =
      buffer += b.toChar
      if b == '\n' then
        val line = buffer.result()
        buffer.clear()
        // we have some dependencies (scribe and friends), which still use scala
        // < 3.8.0, which cause sun.misc.Unsafe::objectFieldOffset warnings.
        // This is a CLI app, the warnings are not actionable for users and
        // cannot be suppressed via configuration in a native image, so we
        // filter them out here.
        if !line.startsWith("WARNING: ") then
          err.print(line)
  })
  System.setErr(newErr)
  clip.main(this, args)
