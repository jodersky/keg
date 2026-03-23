

- `FormParser` & `FormVisitor`
- `FromForm` & `FormResult`

```scala
case class Foo(
    @field("bla")
    name: String,
    foo: Int,
    bar: Boolean,
    file: TempFile
) derives FromForm:
  validate(
    foo > 2
  )
```
