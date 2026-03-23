package keg.dispatch

enum Result[+S, +F]:
  /** Success case */
  case Success(value: S)
  /** Failure case with code and value */
  case Failure(code: Int, value: F)
  case Exception(exception: Throwable) extends Result[Nothing, Nothing]

  def map[B](f: S => B): Result[B, F] = this match
    case Success(value) => Success(f(value))
    case Failure(code, value) => Failure(code, value)
    case e: Exception => e

  def mapFailure[G](f: F => G): Result[S, G] = this match
    case Success(value) => Success(value)
    case Failure(code, value) => Failure(code, f(value))
    case e: Exception => e

  def flatMap[B, C >: F](f: S => Result[B, C]): Result[B, C] = this match
    case Success(value) => f(value)
    case Failure(code, value) => Failure(code, value)
    case e: Exception => e
