package oauth2

import de.mkammerer.argon2.Argon2Factory

object Password:
  val Iterations = 2
  val Memory = 65536
  val Parallelism = 1

  def hash(password: String): String =
    val instance = Argon2Factory.create()
    instance.hash(Iterations, Memory, Parallelism, password.toCharArray)

  def compare(hash: String, password: String): Boolean =
    val instance = Argon2Factory.create()
    instance.verify(hash, password.toCharArray)
