package app

// Keg supports two kinds of form data:
//
// - **URL-encoded** (`application/x-www-form-urlencoded`): the default for
//   HTML forms without file inputs.
// - **Multipart** (`multipart/form-data`): used when forms include file
//   uploads.
//
// #### URL-encoded forms
//
// Use `keg.FormData` to read URL-encoded submissions. Fields are accessible
// by name via `form.fields`. Field data is capped at 30 KB by default; larger
// submissions are rejected with `413 Content Too Large`.
//snippet:start
@keg.post("/greet")
def greet(form: keg.FormData) =
  val name = form.fields.getOrElse("name", "world")
  s"Hello, $name!\n"
//snippet:end
/* usage snippet
$ curl http://localhost:8080/greet -d "name=Alice"
Hello, Alice!
*/

// #### Multipart forms and file uploads
//
// Use `keg.UploadFormData` to handle `multipart/form-data` requests. Plain
// fields are in `form.fields`; uploaded files are in `form.files`. Each file
// entry carries the original filename and a `java.nio.file.Path` pointing to a
// temporary copy on disk — keg cleans it up automatically when the value is
// garbage-collected.
//
// The default limits are 30 KB for plain fields and 100 MB per uploaded file;
// submissions that exceed these limits are rejected with `413 Content Too
// Large`.
//snippet:start
@keg.post("/upload")
def upload(form: keg.UploadFormData) =
  val label = form.fields.getOrElse("label", "file")
  val file = form.files("content")
  val bytes = java.nio.file.Files.readAllBytes(file.path)
  s"$label (${file.filename}): ${new String(bytes)}\n"
//snippet:end
/* usage snippet
$ printf "hello keg" | curl http://localhost:8080/upload \
  -F "label=message" \
  -F "content=@-;filename=note.txt"
message (note.txt): hello keg
*/

val routes = keg.routes()

@main
def main() =
  keg.Server(routes).start()
