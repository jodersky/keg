package keg.util.forms

import keg.model.FormData

trait FormApi extends keg.derivation.BaseApi:

  given FormFromRequest[A](
    using fr: FromForm[A], dr: FromRequest[keg.model.FormData]): FromRequest[Form[A]] with
      def fromRequest(request: keg.model.Request): keg.derivation.Result[Form[A]] =
        dr.fromRequest(request) match
          case keg.derivation.Result.Respond(response) =>
            keg.derivation.Result.Respond(response)
          case keg.derivation.Result.Continue(formData) =>
            val form = fr.parse(formData)
            keg.derivation.Result.Continue(form)

trait FromForm[A]:
  def parse(data: FormData): Form[A]

object FromForm:

  def derived[A] = ???

trait FormFieldReader[A]:
  def read(value: String): Either[String, A]

object FormFieldReader:
  given stringFieldReader: FormFieldReader[String] with
    def read(value: String): Either[String, String] = Right(value)
  given intFieldReader: FormFieldReader[Int] with
    def read(value: String): Either[String, Int] =
      try
        Right(value.toInt)
      catch
        case _: NumberFormatException => Left(s"Invalid integer: $value")

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
