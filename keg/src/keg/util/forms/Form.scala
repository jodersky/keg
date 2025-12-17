package keg.util.forms

trait FormApi extends keg.derivation.BaseApi:

  given FormFromRequest[A](using fr: FromForm[A]): FromRequest[Form[A]] with
    def fromRequest(request: keg.model.Request): keg.derivation.Result[Form[A]] =
      val bytes = request.body.readAllBytes()
      val formData = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      val entries = formData.split("&").toSeq.flatMap { pair =>
        pair.split("=", 2) match
          case Array(key, value) => Some((java.net.URLDecoder.decode(key, "UTF-8"), java.net.URLDecoder.decode(value, "UTF-8")))
          case Array(key) => Some((java.net.URLDecoder.decode(key, "UTF-8"), ""))
          case _ => None
      }
      keg.derivation.Result.Continue(fr.parse(entries))

trait FromForm[A]:
  def parse(entries: Seq[(String, String)]): Form[A]

object FromForm:

  def derived[A] = ???

// this represents a form result
enum Form[+A]:
  case Ok(data: A) extends Form[A]
  case Errors(errors: Seq[FormError])

case class FormError(
  name: Option[String], // the name of the field, or None for global errors
  value: Option[String], // the invalid value, if applicable
  message: String
)

// @forms.form(render: ???)
case class LoginForm(
  username: String,
  password: String,
) derives FromForm

// @app.post("/login")
// def login(form: Form[LoginForm]) =
//   form match
//     case Form.Ok(data) =>
//       // process login
//       ???
//     case Form.Errors(errors) =>
//       // render login page with errors

// def render(value: A, errors: Seq[FormError]): keg.model.Response =
//   ???
