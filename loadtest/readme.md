# Load tests

Macro benchmarks for keg: a real server process, driven over real sockets by
[oha](https://github.com/hatoo/oha). They are meant for comparing transports
(and changes to them).

- `server/` is the server under test. It is a small keg app with one endpoint
  per code path, built for the JVM (undertow or uhttp) and for Scala Native
  (uhttp).
- `runner/` is the orchestrator. For every target, it starts the server as a
  separate process and runs every scenario against it: first a warmup, then
  one measured run per connection count.

## Prerequisites

- `oha` on the `PATH` (e.g. `cargo install oha`)
- the usual Scala Native toolchain (clang), for the native target
- optionally `taskset` (util-linux), for CPU pinning

## Running

```
./mill loadtest.runner.run
```

This builds both server variants, runs all scenarios against all targets, and
prints a summary table. Mill builds the servers before the runner starts, so
the results always reflect the current code. After a change to keg, this
includes relinking the native server, which takes a couple of minutes even if
only JVM targets are selected.

A full run with the defaults takes about 15 minutes (3 targets, 7 scenarios,
a 10s warmup and 2 × 15s runs each). For a quick check while developing, narrow
it down:

```
./mill loadtest.runner.run --target jvm-uhttp --scenario plaintext --connections 32 --duration 5s --warmup 2s
```

Run `./mill loadtest.runner.run --help` for all options. The most important
ones are:

| option | default | description |
|---|---|---|
| `--target` | all | target to run, repeatable |
| `--scenario` | all | scenario to run, repeatable |
| `--connections` | 16 and 256 | concurrent connections, repeatable; one measured run each |
| `--duration` | 15s | length of each measured run |
| `--warmup` | 60s | length of the warmup before each scenario, using the highest connection count |
| `--request-timeout` | 10s | requests taking longer count as failed |
| `--rate` | 0 | fixed rate in requests per second, with latency correction; 0 sends as fast as possible |
| `--server-cpus`, `--client-cpus` | none | pin the server and oha to CPUs, as a taskset list, e.g. `0-3` |
| `--java-opts` | `-Xms1g -Xmx1g` | JVM options for JVM targets |
| `--interceptors` | 0 | number of no-op interceptors installed in the server |
| `--port` | 18080 | port of the server under test |
| `--out` | see below | results directory, relative to the repository root |

### Targets

| target | platform | transport |
|---|---|---|
| `jvm-undertow` | JVM | undertow |
| `jvm-uhttp` | JVM | uhttp |
| `native-uhttp` | Scala Native | uhttp |

### Scenarios

| scenario | request | what it measures |
|---|---|---|
| `plaintext` | `GET /plaintext` | raw transport and framework overhead |
| `plaintext-close` | `GET /plaintext`, new connection per request | connection setup |
| `json` | `GET /json` | JSON serialization; keg streams these, so the response is chunked |
| `json-buffered` | `GET /json/buffered` | the same payload with a Content-Length, to compare against `json` |
| `json-post` | `POST /json` | JSON parsing and serialization |
| `echo` | `POST /echo`, 1 KiB body | reading and writing request bodies |
| `params` | `GET /params/42/alice` | routing with path parameters |

Before a scenario is run, a single request is made to check that it returns
200, so that a broken endpoint isn't silently benchmarked.

To add a scenario, add an endpoint to `server/src/App.scala` if needed, and an
entry to `scenarios` in `runner/src/Main.scala`. To add a target, e.g. a new
transport, register it in `server/src-jvm/transports.scala` (or `src-native`)
and add an entry to `targets`.

## Results

Each run writes to `out/loadtest/results/<time>-<git describe>/`:

- `summary.tsv`: the summary table, one line per measured run
- `metadata.json`: machine (CPU, kernel, frequency governor), tool versions
  and the configuration, to know what a result was measured with
- `<target>/<scenario>-c<connections>.json`: the full oha report, including
  latency histograms
- `<target>/server.log`: output of the server; the runner warns if it contains
  exceptions
- `bodies/`: the request bodies that were sent

The summary columns are requests per second, latency percentiles and the
maximum in milliseconds, and `failed`: non-2xx responses plus client-side
errors (timeouts, closed connections). A non-zero `failed` count means the
other numbers for that run shouldn't be trusted.

`out/` is gitignored, so copy results elsewhere if you want to keep them.

## Getting meaningful numbers

Load tests are noisy, and a few settings affect results more than most code
changes do:

- **Pin the server and oha to separate CPUs**, e.g. `--server-cpus 0-3
  --client-cpus 4-7`, otherwise both compete for the same cores. Check the core
  layout with `lscpu -e`, and avoid splitting hyperthreads of one core between
  the two.
- **Use the `performance` CPU frequency governor** and a machine that is
  otherwise idle, plugged in if it's a laptop. The governor is recorded in
  `metadata.json`.
- **Compare latency at a fixed rate**, not only maximum throughput. Measure the
  maximum first, then rerun with `--rate` at e.g. 50% and 80% of it and compare
  the percentiles. At full speed, a slow server also slows down how fast
  requests are sent, which hides latency spikes.
- **Repeat runs** and look at the variance before concluding that one result is
  better than another.
- **Only compare results from the same machine and configuration.** Loopback
  results are fine for comparing transports against each other, but say little
  about absolute performance.

## Running the server on its own

The server can also be started by hand, e.g. to profile it or to use another
load generator:

```
./mill show loadtest.server.jvm.assembly
java -jar out/loadtest/server/jvm/assembly.dest/out.jar --transport uhttp --port 8080

./mill show loadtest.server.native.nativeLink
out/loadtest/server/native/nativeLink.dest/out --port 8080
```

Run it with `--help` for its options. Once it accepts connections, it prints a
line starting with `listening on`.
