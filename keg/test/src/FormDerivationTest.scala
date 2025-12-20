import utest.*

import keg.util.forms.Form
import keg.util.forms.FromForm
import keg.util.forms.FormData

object FormDerivationTest extends TestSuite:

  case class SimpleForm(name: String, age: Int)

  case class OptionalForm(email: Option[String], score: Option[Int])

  val tests = Tests:

    test("derive FromForm for SimpleForm"):
      val fromForm = FromForm.derived[SimpleForm]
      val data = FormData(Seq(
        keg.util.forms.FormPart.Field("name", "Alice"),
        keg.util.forms.FormPart.Field("age", "30")
      ))
      val form = fromForm.parse(data)
      assert(form.value.contains(SimpleForm("Alice", 30)))

    test("derive FromForm for OptionalForm with all fields"):
      val fromForm = FromForm.derived[OptionalForm]
      val data = FormData(Seq(
        keg.util.forms.FormPart.Field("email", "user@example.com"),
        keg.util.forms.FormPart.Field("score", "85")
      ))
      val form = fromForm.parse(data)
      assert(form.value.contains(OptionalForm(Some("user@example.com"), Some(85))))
