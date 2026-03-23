import utest.*

import keg.forms.FormResult
import keg.forms.FromForm
import keg.forms.FormData
import keg.forms.FormPart

object FormDerivationTest extends TestSuite:

  case class SimpleForm(name: String, age: Int)

  case class OptionalForm(email: Option[String], score: Option[Int])

  val tests = Tests:

    test("derive FromForm for SimpleForm"):
      val fromForm = FromForm.derived[SimpleForm]
      val data = FormData(Seq(
        FormPart.Field("name", "Alice"),
        FormPart.Field("age", "30")
      ))
      val form = fromForm.parse(data)
      assert(form.isInstanceOf[FormResult.Success[SimpleForm]])
      assert(form.asInstanceOf[FormResult.Success[SimpleForm]].value == SimpleForm("Alice", 30))

    test("derive FromForm for OptionalForm with all fields"):
      val fromForm = FromForm.derived[OptionalForm]
      val data = FormData(Seq(
        FormPart.Field("email", "user@example.com"),
        FormPart.Field("score", "85")
      ))
      val form = fromForm.parse(data)
      assert(form.isInstanceOf[FormResult.Success[OptionalForm]])
      assert(form.asInstanceOf[FormResult.Success[OptionalForm]].value == OptionalForm(Some("user@example.com"), Some(85)))
