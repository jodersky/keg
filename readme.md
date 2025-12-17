# keg

A small web framework, merging ideas from flask, rocket and especially reusing
many ideas from [cask](https://github.com/com-lihaoyi/cask).

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

- Change macros and decorators to differentiate between path parameters and
  parameters inferred from requests.

  As of this writing, every cask decorator has a `wrapPathSegment` method, which
  converts a string from a dynamic path segment to some intermediate value (the
  decorator's `Input` type) and will then eventually be converted to the method
  parameter's type. This can be awkward in some situations.

- More clearly differentiate between by-name and by-type parameters.

- Restrict endpoint decorators, one per endpoint.

Minor stuff:

- Don't require route declarations to be in subclasses of `cask.Routes`.
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
  object MyRoutes:
    @cask.get("/foo")
    def foo() = ...

    val routes = keg.routes() // finds all methods annotated here, without need of parent to extend a trait

  val routes = Seq(MyRoutes.routes)
  ```

- Change default behavior of allowing extraneous parameters.

  In cask it is by default an error to provide additional arguments which are
  not declared in an endpoint method. You can allow additional parameters by
  accepting a special object (`TODO`), but that needs to happen explicitly.
  In keg, extraneous parameters are by default allowed.

- Change the error model. Instead of a decorator returning an error which will
  be handled later by the server, a decorator MUST always return a response.
  Hence, error handling stays local to the decorator.

These features require some significant changes to cask's decorators, which I
would say are at least 50% of what makes cask cask. Hence changing them would
change cask in everything except the name.

Also, the macros which derive endpoints from annotated methods become more
powerful, and I only really want to work with Scala 3 macros. Hence, I'd need to
drop Scala 2 support for cask.

These changes led me to creating a new framework, `keg`. Essentially, keg is a
different syntax sugar layer for cask. It still uses the same (or similar)
request handling machinery from cask, and uses the same webserver.
