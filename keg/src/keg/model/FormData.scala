package keg.model

/** Represents an urlencoded or multipart/form-data form submission */
case class FormData(
  entries: Seq[(String, FormEntry)]
)

/** Represents a single entry in a form submission, either a value or a file
  * upload.
  *
  * Note that file uploads are opaque in the model. All we the model knows is
  * the file name and the path to a temporary file where the contents are
  * stored. This directory is managed by the server and will be cleaned up
  * automatically.
  */
enum FormEntry:
  case Value(value: String, headers: Seq[(String, String)])
  case File(fileName: String, filePath: java.nio.file.Path, headers: Seq[(String, String)])
