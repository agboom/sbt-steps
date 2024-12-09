package sbtsteps.internal.cli

import sbt.complete.{Parser, DefaultParsers}
import DefaultParsers.*

/** Data type for any abstract argument parsed to a value of type '''T'''.
  * @tparam T
  *   The value type that this argument is parsed to.
  */
sealed trait Arg[+T] {

  /** Parser for the value of this argument.
    */
  def parser: Parser[T]

  /** Default value if not provided. If '''None''' the argument is required.
    */
  def default: Option[T]
}

/** Command line option with a short (-s) and long (--status) notation.
  */
sealed trait Opt[+T] extends Arg[T] {

  /** The short notation for this option (e.g. -s)
    */
  def short: Char

  /** The long notation for this option (e.g. --status)
    */
  def long: String

  override def toString: String = s"-$short|--$long"
}

/** Boolean type option that is set to true if passed and false if not.
  */
final case class Flag(short: Char, long: String) extends Opt[Boolean] {

  final override val parser: Parser[Boolean] = DefaultParsers.Bool

  final override val default: Option[Boolean] = Some(false)

}

/** Argument option that provides a value of type '''T''' if parsed
  * successfully.
  *
  * @note
  *   If an option is passed multiple times, the first value wins.
  */
final case class ArgOpt[T](
  short: Char,
  long: String,
  parser: Parser[T],
  default: Option[T] = None,
) extends Opt[T] {

  def withDefault(default: T): ArgOpt[T] = copy(default = Some(default))
}

sealed trait PosArg[+T] extends Arg[T]

object PosArg {
  def apply[T](
    name: String,
    parser: Parser[T],
    default: Option[T] = None,
  ): Single[T] =
    Single(name, parser, default)

  /** Positional argument passed after options providing a single value of type
    * '''T''' if parsed successfully.
    * @param name
    *   The name of this argument (printed if a required argument is missing).
    */
  case class Single[T](
    name: String,
    parser: Parser[T],
    default: Option[T] = None,
  ) extends PosArg[T] {
    def withDefault(default: T): Single[T] = copy(default = Some(default))

    override def toString: String = s"<$name>"
  }

  /** Multiple positional arguments passed after options providing a list of
    * '''T''' values if parsed successfully If a default is provided, it's used
    * whenever the list is empty.
    * @param name
    *   The name of this argument list.
    */
  final case class Multi[T](
    name: String,
    singleParser: Parser[T],
    default: Option[Seq[T]] = None,
  ) extends PosArg[Seq[T]] { self =>

    final val parser: Parser[Seq[T]] =
      repsep(
        singleParser & not('-' || Space || EOF, "Expected positional argument"),
        Space.*,
      )

    def withDefault(default: Seq[T]): Multi[T] = copy(default = Some(default))

    override def toString: String = s"<$name...>"
  }
}

/** Argument with its successfully parsed value.
  */
case class ParsedArg[T](arg: Arg[T], value: T)
