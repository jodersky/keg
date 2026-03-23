package keg.forms

case class Form[A](

)

case class UpdatePassword(
    currentPassword: String,
    newPassword: String,
    confirmPassword: String
) derives FromForm



case class Login(
    username: String,
    password: String,
    rememberMe: Boolean
) derives FromForm

case class Form[A](
  value: Option[A] = None,
  fields: Map[String, String] = Map.empty,
  errors: Map[String, String] = Map.empty
):
  def render(


  ): String = ???

def renderForm

case class FromForm[A] extends FromRequest[Form[A]]:
  def FromRequest(): From[A] = ???

@post("/change-password")
def changePasswordForm(pw: Form[UpdatePassword]): String =
  s"Changing password for user with current password: ${pw.currentPassword}"
  // check

  if (pw.newPassword != pw.confirmPassword) {
    "New password and confirmation do not match."
  } else {
    "Password changed successfully."
  }

  pw.render(
    globalError = "Password must be strong",
    fieldErrors = Map(
      "newPassword" -> "Password must be at least 8 characters long"
    )
  )

  frag(
    alert("Password changed successfully!", "success"),
    renderNewForm()
  )


