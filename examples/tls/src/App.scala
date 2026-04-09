package app

import java.nio.file.Path

// Support for HTTPS is built in. You just need to provide a certificate and key
// as standard PEM files, and configure the server to listen for HTTPS requests.
//snippet:start
@keg.get("/hello")
def hello() =
  "hello world\n"

val server = keg.Server(
  keg.routes().endpoints,
  listeners = Seq(
    keg.Listener.Https(
      host = "::",
      port = 8443,
      key = Path.of("creds/server.key"),
      cert = Path.of("creds/server.crt")
    )
  )
)
//snippet:end
/* usage snippet
$ curl "https://localhost:8443/hello" --cacert creds/server-ca.crt
hello world
*/

// It's also possible to use a client certificate for authentication. Add the
//`clientCa` parameter:
//snippet:start
val serverWithClientAuth = keg.Server(
  keg.routes().endpoints,
  listeners = Seq(
    keg.Listener.Https(
      host = "::",
      port = 8443,
      key = Path.of("creds/server.key"),
      cert = Path.of("creds/server.crt"),
      clientCa = Some(Path.of("creds/client-ca.crt"))
    )
  )
)

// You can then access the client certificate in your endpoints.
@keg.get("/whoami")
def whoami(req: keg.Request) =
  req.clientCertificate match
    case Some(cert) => s"Your client certificate subject is: ${cert.subject}\n"
    case None => "No client certificate provided\n"
//snippet:end
/* usage snippet
$ curl "https://localhost:8443/whoami" --cacert creds/server-ca.crt --cert creds/client.crt --key creds/client.key
Your client certificate subject is: CN=client
*/

// > [!IMPORTANT]
// >
// > **It is up to your application to check for the presence of a client
// > certificate**, keg will still accept requests without a client certificate.
// > It only guarantees that if `request.clientCertificate` is `Some(cert)`
// > that it has been signed by the specified CA and is still valid.

@main
def main() =
  serverWithClientAuth.start() // we only use this one to test, it also does the non-mutual TLS tests
