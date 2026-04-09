package keg.model

/** Form data which supports file uploads.
  *
  * As of now, uploaded files are deleted when they are garbage collected, but
  * this may change in the future if we ever need to revisit lifecycle
  * management of request data.
  */
case class UploadFormData(
  fields: Map[String, String],
  files: Map[String, UploadFormData.File]
)

object UploadFormData:
  private val cleaner = java.lang.ref.Cleaner.create()

  case class File(filename: String, path: java.nio.file.Path)

  class Builder(dir: java.nio.file.Path) extends keg.forms.FormVisitor[UploadFormData]:
    private val fields = scala.collection.mutable.Map.empty[String, String]
    private val files = scala.collection.mutable.Map.empty[String, File]
    private var stream: java.io.OutputStream = scala.compiletime.uninitialized

    override def visitField(name: String, value: String): Unit =
      fields += name -> value

    override def visitFileBegin(name: String, filename: String): java.io.OutputStream =
      val path = java.nio.file.Files.createTempFile(dir, "upload-", ".tmp")
      path.toFile.deleteOnExit()
      files += name -> File(filename, path)
      cleaner.register(this, () => java.nio.file.Files.deleteIfExists(path))
      stream = java.nio.file.Files.newOutputStream(path)
      stream

    override def visitFileEnd(): Unit =
      if stream != null then
        stream.close()
        stream = null

    override def visitEnd(): UploadFormData =
      UploadFormData(fields.toMap, files.toMap)
