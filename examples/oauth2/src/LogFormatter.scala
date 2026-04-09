package oauth2

object LogFormatter extends scribe.format.Formatter:
  override def format(record: scribe.LogRecord): scribe.output.LogOutput =
    val json = ujson.Obj(
      "timestamp" -> record.timeStamp.toString,
      "level" -> record.level.name,
      "loggerName" -> record.loggerName,
      "message" -> record.messages.map(_.logOutput.plainText).mkString(" "),
      "threadName" -> record.thread.getName(),
      "data" -> record.data.map { case (k, v) => k -> v().toString }.toMap,
      // "throwable" -> record. .map(t => scribe.format.Formatter.defaultThrowableFormat(t)).getOrElse("")
    )
    scribe.output.TextOutput(json.render())
