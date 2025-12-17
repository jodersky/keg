package keg.model

enum FormEntry:
  case Value(value: String, headers: Seq[(String, String)])
  case File(fileName: String, filePath: java.nio.file.Path, headers: Seq[(String, String)])
