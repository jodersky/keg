package loadtest.runner

import scala.concurrent.duration.*

/** A transport on a platform, i.e. one variant of the server under test. */
case class Target(name: String, platform: String, transport: String)

val targets = Seq(
  Target("jvm-undertow", "jvm", "undertow"),
  Target("jvm-uhttp", "jvm", "uhttp"),
  Target("native-uhttp", "native", "uhttp")
)

/** A request which is sent repeatedly to the server under test. */
case class Scenario(
  name: String,
  path: String,
  method: String = "GET",
  contentType: Option[String] = None,
  body: Array[Byte] = Array.empty,
  // if false, a new connection is opened for every request
  keepAlive: Boolean = true
)

val scenarios = Seq(
  Scenario("plaintext", "/plaintext"),
  Scenario("plaintext-close", "/plaintext", keepAlive = false),
  Scenario("json", "/json"),
  Scenario("json-buffered", "/json/buffered"),
  Scenario(
    "json-post", "/json", "POST",
    Some("application/json"), """{"message":"Hello, World!"}""".getBytes("UTF-8")
  ),
  Scenario(
    "echo", "/echo", "POST",
    Some("application/octet-stream"), scala.util.Random(0).nextBytes(1024)
  ),
  Scenario("params", "/params/42/alice")
)

def log(message: String): Unit =
  val time = java.time.LocalTime.now().withNano(0)
  clip.log(s"[$time] $message")

def selectByName[A](kind: String, all: Seq[A], names: Seq[String], name: A => String): Seq[A] =
  if names.isEmpty then all
  else
    for n <- names yield all.find(name(_) == n).getOrElse(
      clip.abort(s"unknown $kind '$n', available: ${all.map(name).mkString(", ")}", 2)
    )

def pinned(cpus: String, command: Seq[String]): Seq[String] =
  if cpus.isEmpty then command
  else Seq("taskset", "-c", cpus) ++ command

def portInUse(port: Int): Boolean =
  try
    java.net.Socket("127.0.0.1", port).close()
    true
  catch case _: java.io.IOException => false

// oha accepts durations with units
def ohaDuration(d: Duration): String = s"${d.toMillis}ms"

def commandOutput(command: String*): Option[String] =
  try
    val result = os.proc(command).call(check = false, mergeErrIntoOut = true)
    if result.exitCode == 0 then Some(result.out.trim()) else None
  catch case _: java.io.IOException => None

/** Makes a single request, to avoid benchmarking an endpoint that fails. */
def checkScenario(scenario: Scenario, url: String): Unit =
  import java.net.http.{HttpClient, HttpRequest, HttpResponse}
  val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
  val request = HttpRequest.newBuilder(java.net.URI.create(url + scenario.path))
    .method(scenario.method, HttpRequest.BodyPublishers.ofByteArray(scenario.body))
  scenario.contentType.foreach(request.header("Content-Type", _))
  val status = client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
  if status != 200 then
    clip.abort(s"scenario ${scenario.name}: expected status 200, got $status", 1)

class Server(target: Target, command: Seq[String], logFile: os.Path, port: Int):
  if portInUse(port) then clip.abort(s"something is already listening on port $port", 1)
  log(s"starting ${target.name}: ${command.mkString(" ")}")
  private val process = os.proc(command).spawn(stdout = logFile, mergeErrIntoOut = true)

  // stop the server if the runner is interrupted
  private val shutdownHook = Thread(() => stop())
  Runtime.getRuntime.addShutdownHook(shutdownHook)

  private val deadline = System.nanoTime() + 30.seconds.toNanos
  while !os.read(logFile).linesIterator.exists(_.startsWith("listening on")) do
    if !process.isAlive() then
      clip.log(os.read(logFile))
      clip.abort(s"${target.name} exited during startup", 1)
    if System.nanoTime() > deadline then
      stop()
      clip.abort(s"${target.name} did not start within 30 seconds", 1)
    Thread.sleep(100)

  // terminates the server, and kills it if it doesn't exit within 10 seconds
  def stop(): Unit =
    if process.isAlive() then process.destroy(shutdownGracePeriod = 10000)

  def close(): Unit =
    stop()
    Runtime.getRuntime.removeShutdownHook(shutdownHook)
    if os.read(logFile).contains("Exception") then
      log(s"warning: ${target.name} logged exceptions, see $logFile")

case class Row(
  target: String,
  scenario: String,
  connections: Int,
  requestsPerSec: Double,
  p50: Double,
  p99: Double,
  p999: Double,
  max: Double,
  failed: Long
)

def readReport(target: Target, scenario: Scenario, connections: Int, report: os.Path): Row =
  val json = ujson.read(os.read(report))
  // oha reports seconds, the summary uses milliseconds
  def millis(value: ujson.Value): Double =
    if value.isNull then 0 else math.round(value.num * 1e6) / 1e3
  val non2xx = json("statusCodeDistribution").obj.collect:
    case (status, count) if !status.startsWith("2") => count.num.toLong
  val errors = json("errorDistribution").obj.values.map(_.num.toLong)
  Row(
    target.name,
    scenario.name,
    connections,
    json("summary")("requestsPerSec").num,
    millis(json("latencyPercentiles")("p50")),
    millis(json("latencyPercentiles")("p99")),
    millis(json("latencyPercentiles")("p99.9")),
    millis(json("summary")("slowest")),
    non2xx.sum + errors.sum
  )

@clip.command(
  name = "loadtest",
  help = "Load test keg's transports with oha. For every target, the server under " +
    "test is started as a separate process, and every scenario is run against it: " +
    "first a warmup, then one measured run per connection count."
)
def runLoadTest(
  @clip.arg("--target", repeats = true, help = s"Target to run, default all: ${targets.map(_.name).mkString(", ")}")
  targetNames: Seq[String] = Seq(),
  @clip.arg("--scenario", repeats = true, help = s"Scenario to run, default all: ${scenarios.map(_.name).mkString(", ")}")
  scenarioNames: Seq[String] = Seq(),
  @clip.arg("--connections", repeats = true, help = "Number of concurrent connections, one measured run each; default 16 and 256")
  connectionCounts: Seq[Int] = Seq(),
  @clip.arg("--duration", help = "Length of each measured run")
  duration: Duration = 15.seconds,
  @clip.arg("--warmup", help = "Length of the warmup before each scenario")
  warmup: Duration = 60.seconds,
  @clip.arg("--request-timeout", help = "Requests taking longer are counted as failed")
  requestTimeout: Duration = 10.seconds,
  @clip.arg("--rate", help = "Fixed rate in requests per second, with latency correction; 0 sends as fast as possible")
  rate: Int = 0,
  @clip.arg("--server-cpus", help = "CPUs to pin the server to, as a taskset list, e.g. 0-3")
  serverCpus: String = "",
  @clip.arg("--client-cpus", help = "CPUs to pin oha to, as a taskset list, e.g. 4-7")
  clientCpus: String = "",
  @clip.arg("--java-opts", help = "JVM options for jvm targets")
  javaOpts: String = "-Xms1g -Xmx1g",
  @clip.arg("--interceptors", help = "Number of no-op interceptors installed in the server")
  interceptors: Int = 0,
  @clip.arg("--port", help = "Port for the server under test")
  port: Int = 18080,
  @clip.arg("--out", help = "Directory for results, relative to the workspace; default out/loadtest/results/<time>-<git describe>")
  out: String = ""
): Unit =
  val workspace = sys.env.get("KEG_WORKSPACE").map(os.Path(_)).getOrElse(os.pwd)
  val selectedTargets = selectByName("target", targets, targetNames, _.name)
  val selectedScenarios = selectByName("scenario", scenarios, scenarioNames, _.name)
  val connections = if connectionCounts.isEmpty then Seq(16, 256) else connectionCounts
  val url = s"http://127.0.0.1:$port"

  val ohaVersion = commandOutput("oha", "--version").getOrElse(clip.abort("oha is not installed", 1))
  val gitVersion = commandOutput("git", "-C", workspace.toString, "describe", "--tags", "--always", "--dirty").getOrElse("unknown")

  val platforms = selectedTargets.map(_.platform).toSet

  // When run via mill, the server executables are built beforehand and passed
  // in the environment.
  def executable(variable: String): os.Path =
    sys.env.get(variable).map(os.Path(_)).filter(os.exists(_)).getOrElse(
      clip.abort(s"server executable not found in $variable, run with ./mill loadtest.runner.run", 1)
    )

  def serverCommand(target: Target): Seq[String] =
    val command = target.platform match
      case "jvm" =>
        val jar = executable("KEG_LOADTEST_JAR")
        Seq("java") ++ javaOpts.split("\\s+").filter(_.nonEmpty) ++ Seq("-jar", jar.toString)
      case "native" =>
        Seq(executable("KEG_LOADTEST_NATIVE").toString)
    pinned(serverCpus, command) ++ Seq(
      "--transport", target.transport,
      "--port", port.toString,
      "--interceptors", interceptors.toString
    )

  // fail before running anything if an executable is missing
  selectedTargets.foreach(serverCommand)

  val outDir =
    if out.nonEmpty then os.Path(out, workspace)
    else
      val time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
      workspace / "out" / "loadtest" / "results" / s"$time-$gitVersion"
  os.makeDir.all(outDir)

  os.write.over(
    outDir / "metadata.json",
    ujson.write(
      ujson.Obj(
        "date" -> java.time.OffsetDateTime.now().toString,
        "git" -> gitVersion,
        "kernel" -> commandOutput("uname", "-srm").getOrElse("unknown"),
        "cpu" -> os.read.lines(os.root / "proc" / "cpuinfo")
          .find(_.startsWith("model name")).map(_.dropWhile(_ != ':').drop(1).trim).getOrElse("unknown"),
        "cpus" -> Runtime.getRuntime.availableProcessors,
        "governor" -> commandOutput("cat", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").getOrElse("unknown"),
        "oha" -> ohaVersion,
        "java" -> (if platforms.contains("jvm") then commandOutput("java", "-version").map(_.linesIterator.next()).getOrElse("unknown") else "unused"),
        "config" -> ujson.Obj(
          "targets" -> selectedTargets.map(_.name),
          "scenarios" -> selectedScenarios.map(_.name),
          "connections" -> connections,
          "duration" -> duration.toString,
          "warmup" -> warmup.toString,
          "requestTimeout" -> requestTimeout.toString,
          "rate" -> rate,
          "serverCpus" -> serverCpus,
          "clientCpus" -> clientCpus,
          "javaOpts" -> javaOpts,
          "interceptors" -> interceptors
        )
      ),
      indent = 2
    )
  )
  log(s"writing results to $outDir")

  def runOha(scenario: Scenario, extraArgs: Seq[String]): Unit =
    val bodyArgs = scenario.contentType match
      case Some(contentType) =>
        val bodyFile = outDir / "bodies" / s"${scenario.name}.bin"
        if !os.exists(bodyFile) then os.write(bodyFile, scenario.body, createFolders = true)
        Seq("-T", contentType, "-D", bodyFile.toString)
      case None => Seq()
    val command = Seq("oha", "--no-tui", "-w", "-t", ohaDuration(requestTimeout)) ++
      extraArgs ++
      Seq("-m", scenario.method) ++
      bodyArgs ++
      (if scenario.keepAlive then Seq() else Seq("--disable-keepalive")) ++
      Seq(url + scenario.path)
    os.proc(pinned(clientCpus, command)).call(stdout = os.Inherit)

  val rows = collection.mutable.ListBuffer.empty[Row]

  for target <- selectedTargets do
    val targetDir = outDir / target.name
    os.makeDir.all(targetDir)
    val server = Server(target, serverCommand(target), targetDir / "server.log", port)
    try
      for scenario <- selectedScenarios do
        checkScenario(scenario, url)

        log(s"${target.name} ${scenario.name}: warmup for $warmup")
        runOha(scenario, Seq("-z", ohaDuration(warmup), "-c", connections.max.toString, "--output-format", "quiet"))

        for conns <- connections do
          log(s"${target.name} ${scenario.name}: $conns connections for $duration")
          val report = targetDir / s"${scenario.name}-c$conns.json"
          val rateArgs = if rate > 0 then Seq("-q", rate.toString, "--latency-correction") else Seq()
          runOha(
            scenario,
            Seq("-z", ohaDuration(duration), "-c", conns.toString) ++ rateArgs ++
              Seq("--output-format", "json", "-o", report.toString)
          )
          rows += readReport(target, scenario, conns, report)
    finally server.close()

  val header = Seq("target", "scenario", "conns", "req/s", "p50 ms", "p99 ms", "p99.9 ms", "max ms", "failed")
  val cells = rows.toSeq.map: row =>
    Seq(
      row.target, row.scenario, row.connections.toString, math.round(row.requestsPerSec).toString,
      row.p50.toString, row.p99.toString, row.p999.toString, row.max.toString, row.failed.toString
    )
  os.write.over(outDir / "summary.tsv", (header +: cells).map(_.mkString("\t")).mkString("", "\n", "\n"))
  clip.tabulate(header, cells)

def main(args: Array[String]): Unit = clip.main(this, args)
