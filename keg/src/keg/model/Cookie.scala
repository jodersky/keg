package keg.model

case class Cookie(
    name: String,
    value: String,
    comment: String = null,
    domain: String = null,
    expires: java.time.Instant = null,
    maxAge: Integer = null,
    path: String = null,
    version: Int = 1,
    discard: Boolean = false,
    httpOnly: Boolean = false,
    secure: Boolean = false,
    sameSite: String = "Lax"
)
