package keg.forms

/** Raw form data, as transmitted in a form submission. */
case class FormData(fields: collection.Map[String, String])
