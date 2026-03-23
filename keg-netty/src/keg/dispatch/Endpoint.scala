package keg.dispatch

import keg.model.Request
import keg.model.Response

case class Endpoint(
  fqn: String, // name of the endpoint function
  path: String,
  methods: Seq[String], // HTTP methods
  invoke: Request => Result[Any, Nothing] // Failure cases are handled by the framework
)
