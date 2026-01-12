# keg

A small web framework, merging ideas from
[flask](https://flask.palletsprojects.com/en/stable/),
[rocket](https://rocket.rs/) and especially reusing many ideas from
[cask](https://github.com/com-lihaoyi/cask).

Keg's focus is around extensible request-scoped state (e.g. adding the logged in
user to a request, or checking authorization).

## Managing state

State management and dependency injection is often a key part of web frameworks
that is prone to scope creep.

Keg takes on a very limited role in state management: essentially, it only
focuses on mechanisms to inject request-scoped state. Application state is not
managed by keg, and users are free to use whatever they wish, be it plain
classes with parameters or anything else.

## Differences with cask

Initially I wanted to augment cask with the following features.

Major changes:

- Completely rework endpoint decorators; don't use custom ones

  (with *endpoint* decorator I mean the cask top-level annotation which extends
  `cask.router.Endpoint`, not the regular decorators which can additionally be
  applied to endpoints)

  I would say that cask's endpoint decorator system is one of its most defining
  and most powerful features (the other IMO being the request/response modeling
  which "just works" with many data types). They define how a request will be
  parsed into scala types and thus act as the glue between the HTTP model and
  the business logic.

  While cask comes with many decorators for different parsing requirements (e.g.
  raw requests, forms, json, websockets), in my experience I often find myself
  needing to define custom decorators, at the very least once per project.
  Furthermore, I often find myself duplicating some of the logic, especially
  regarding defining wrapper types for `InputParser`s so that I can get by-type
  lookup of parameters.

  Hence, in keg I have decided to take a different approach. Instead of using
  custom decorators to configure parsing, there is only one decorator `request`
  (as well as convenience decorators which just select a specific HTTP method
  `get`, `put`, `post`, `delete` etc), and all parsing logic happens using
  regular type classes. To ease lookup of said typeclasses without custom
  imports, keg uses "configuration trait" pattern: the `request` decorator is
  part of a trait.

  I realize that this only shifts the problem around, but in my experience the
  main gain is the lack of all the higher-kinded stuff like `InputParser[T]`

- Change macros and decorators to differentiate between path parameters and
  parameters inferred from requests.

  As of this writing, every cask decorator has a `wrapPathSegment` method, which
  converts a string from a dynamic path segment to some intermediate value (the
  decorator's `Input` type) and will then eventually be converted to the method
  parameter's type. This can be awkward in some situations, where the path and
  body of a request should be treated differently (e.g. forms).

  Keg instead requires paths in annotations to be statically known, and uses
  `FromPath[?]` type classes to convert path parameters to the required scala
  type.

  In the following example, during a request to `/42` cask would first convert
  the path to a form element (since `postForm` defines forms as its intermediate
  value type), and then to an `Int`. In keg, the conversion is done directly.

  ```scala
  @cask.postForm("/:foo")
  def fn(foo: Int)
  ```

- More clearly differentiate between by-name and by-type parameters.

  In cask, decorators always set parameters by-name (the next decorator's
  `Delegate` type accepts a `Map[String, Any]`). The way to use by type
  parameters is to implement a custom `InputParser`.

  This approach makes cask very flexible, and in particular allows associating
  meaning to parameter names. For example, in cask the `postJson` decorator will
  accept a JSON object and associate each field to a parameter:

  ```json
  {
    "a": 42,
    "b": "foobar"
  }
  ```
  ```scala
  @cask.postJson("/")
  def fn(a: Int, b: String) // a and b map directly to fields
  ```

  In keg, I have decided do drop by-name parameters except of path segments
  (paths must be static in keg). This in my opinion makes it very
  straightforward to know where a parameter conversion happens:
  - if it's declared in the path, it will be by name (and will use a FromPath[?]
    typeclass as mentioned in the first point)
  - otherwise, there must be a FromRequest typeclass available

  E.g.

  ```scala
  @get("/foo/:bar/:baz")
  def fn(bar: Int, baz: String, extra: String, more: SomeClass)
  ```

  here, `bar` and `baz` are by-name, taken directly from the path. `extra` and
  `more` require a `FromRequest[String]` and `FromRequest[SomeClass]` to be in
  scope.

  This approach has the consequence that parameters names are no longer
  meaningful outside of path parameters. Using the earlier JSON example, in keg
  you can no longer define decorators which would set individual parameters to
  correspond to field names. Instead, you'd need to go through a custom class:

   ```json
  {
    "a": 42,
    "b": "foobar"
  }
  ```
  ```scala
  case class JsonBody(
    a: Int,
    b: String
  ) derives upickle.Reader

  @cask.postJson("/")
  def fn(body: JsonBody)
  ```

  The main reason I have done this is to simplify decorators: there's no more
  intermediate type, so each parameters must be parsed directly from the
  request. The reason for simplifying decorators is described below.

  Note: this design may be revisited; I don't think that there's anything
  technically stopping us from using an intermediate type and mapping parameters
  by name.

- Change the error model. Instead of a decorator returning an error which will
  be handled later by the server, a decorator MUST always return a response.
  Hence, error handling stays local to the decorator.

Minor stuff:

- Reduce need for inheritance in routes.

  Don't require route declarations to be in subclasses of `cask.Routes`.
  Instead, allow finding routes in the current object with a macro.

  E.g. instead of
  ```scala
  object MyRoutes extends cask.Routes:
    @cask.get("/foo")
    def foo() = ...

    initialize()

  val routes = Seq(MyRoutes)
  ```

  do this:

  ```scala
  @keg.get("/foo")
  def foo() = ...

  val routes = keg.routes() // finds all methods annotated here, without need of parent to extend a trait
  ```

  Also, don't require extending a `Main` trait to initialize a server. Instead,
  instantiate a `Server` class with configuration, including routes, as
  parameters.

- Change default behavior of allowing extraneous parameters.

  In cask it is by default an error to provide additional arguments which are
  not declared in an endpoint method. You can allow additional parameters by
  accepting a special object, but that needs to happen explicitly. In keg,
  extraneous parameters are by default allowed. (this is in fact a corollary of
  keg not parsing into intermediate types)

- Isolate references to undertow into the server object.

  The syntax sugar layer and HTTP layer are no longer aware of undertow at all
  (this required me to implement a custom form parser, but that was only a
  mechanical task). Essentially, this means that undertow is now an
  implementation detail and can be switched out later.

These features require some significant changes to cask's decorators, which I
would say are at least 50% of what makes cask cask. Hence changing them would
change cask in everything except the name.

Also, the macros which derive endpoints from annotated methods become more
powerful, and I only really want to work with Scala 3 macros. Hence, I'd need to
drop Scala 2 support for cask.

These changes led me to creating a new framework, `keg`. Essentially, keg is a
different syntax sugar layer for cask. It still uses the same (or similar)
request handling machinery from cask, and uses the same webserver.

## Features

- websockets
- forms with automatic CSRF protection
- signed cookies
- interceptors

## Internals

- division of layers

  (arrow means "depends on")

  ```mermaid
  graph LR
    model[HTTP model and endpoints];
    main[main HTTP dispatching];
    derivation[syntax sugar]
    derivation --> model;
    main --> model;

    derivation --> form[form utils];
    derivation --> json[json];
  ```
