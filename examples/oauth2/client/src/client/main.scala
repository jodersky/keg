package client

@clip.command()
def run() =
  System.setProperty("org.slf4j.simpleLogger.log.io.undertow", "warn")

  val result = flows.authorizationCodeFlow(
    clientId = "4a289809-6d24-4ddf-8844-ceb990d0aa9a"
  )

  println(result)

  // val client = CliClient(
  //   clientId = "4a289809-6d24-4ddf-8844-ceb990d0aa9a",
  //   port = 8082
  // )
  // val tok = client.getToken()
  // println(tok)

// def main(args: Array[String]): Unit = clip.main(this, args)

