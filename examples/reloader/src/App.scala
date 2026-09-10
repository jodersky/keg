package app

import scalatags.Text.all as h
import h.given


def index() =
  h.doctype("html")(
    h.html(
      h.head(
        scalatags.Text.tags2.title("Keg Reloader Example")
      ),
      h.body(
        h.h1("Keg Reloader Example"),
        h.p("This examples shows how HTML can be automatically reloaded whenever the server is restarted.")
      )
    )
  )

val endpoints = Seq(
  keg.Endpoint(
    path = "/",
    invoke = _ => keg.Response(index(), 200)
  )
)

// reloading only works in debug mode, and when an `<!DOCTYPE html>` tag is detected in
// the response
val server = keg.Server(endpoints, debugMode = true)

@main
def main() =
  server.start()
