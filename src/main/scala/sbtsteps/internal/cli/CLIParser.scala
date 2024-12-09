package sbtsteps.internal.cli

import sbt.complete.DefaultParsers.*
import sbt.complete.Parser

/** @define basedesc
  *   Create a parser for a POSIX-style command-line interface with
  * @define posixlink
  *   [[https://www.gnu.org/software/libc/manual/html_node/Argument-Syntax.html]]
  */
object CLIParser {
  private def optParser[O <: Opt[?]](opt: O): Parser[ParsedArg[?]] = {
    def makeParser(short: Boolean) = {
      val lhs =
        (Space ~> token(if (short) s"-${opt.short}" else s"--${opt.long}"))
      val rhs = opt match {
        case argOpt: ArgOpt[?] =>
          (if (short) OptSpace else (Space | '=')) ~> token(
            argOpt.parser,
            argOpt.long,
          ).map(ParsedArg(argOpt, _))
        case flag: Flag =>
          ((if (short) OptSpace else (Space | '=')) ~> token(
            flag.parser,
            flag.long,
          )).?.map(_.getOrElse(true))
            .map(ParsedArg(flag, _))
      }
      lhs ~> rhs
    }

    makeParser(true) | makeParser(false)
  }

  // parse multiple short flags as combinations in arbritary order (e.g. -vs or -sv or -s -v)
  private def flagsParser(flags: Flag*): Parser[Seq[ParsedArg[?]]] =
    Space ~> token('-') ~> chars(flags.map(_.short).mkString).+
      .map(_.flatMap { short =>
        flags.find(_.short == short).map(ParsedArg(_, true))
      })

  // parse singular options, both short and long (-f foo.txt --file=foo.txt)
  // the same option may be repeated, but the first occurrence wins
  private def optsParser(opts: Opt[?]*): Parser[Seq[ParsedArg[?]]] =
    opts.foldLeft(success(Seq.empty[ParsedArg[?]])) {
      case (parser, opt) => parser | optParser(opt).*
    }.map(_.groupBy(_.arg).map(_._2.head).toSeq)

  // parse positional arguments in the order passed to this function
  private def posArgParser(args: PosArg[?]*): Parser[Seq[ParsedArg[?]]] =
    args.foldLeft(success(Seq.empty[ParsedArg[?]])) {
      case (parser, arg: PosArg.Multi[?]) =>
        // allow to leave out the leading space if no arguments are given at the start
        val argParser = (Space.? ~ token(arg.parser, s"$arg")).flatMap {
          case (None, Nil) =>
            success(arg.default.getOrElse(Nil))
          case (Some(_), values) if values.nonEmpty =>
            success(values)
          case _ =>
            failure("Expected whitespace before start of argument list")
        }
        (parser ~ argParser).map {
          case (parsed, value) => parsed :+ ParsedArg(arg, value)
        }
      case (parser, arg: PosArg.Single[?]) =>
        val argParser = Space ~> token(arg.parser, s"$arg")
        val argParserWithDefault = arg.default match {
          case Some(default) => argParser.?.map(_.getOrElse(default))
          case None => argParser
        }
        (parser ~ argParserWithDefault).map {
          case (parsed, value) => parsed :+ ParsedArg(arg, value)
        }
    }

  // combined parser for flags and options in arbritary order followed by positional arguments
  def argParser(args: Arg[?]*): Parser[Seq[ParsedArg[?]]] = {
    val (opts, posArgs) =
      args.foldLeft((Seq.empty[Opt[?]], Seq.empty[PosArg[?]])) {
        case ((opts, posArgs), arg: PosArg[?]) =>
          opts -> (posArgs :+ arg)
        case ((opts, posArgs), arg: Opt[?]) =>
          // check for duplicate short or long notations
          opts.find(opt =>
            opt.long == arg.long || opt.short == arg.short,
          ) match {
            case Some(opt) =>
              throw new RuntimeException(s"Ambiguous notation in $opt and $arg")
            case None =>
              (opts :+ arg) -> posArgs
          }
      }
    (flagsParser(opts.collect { case f: Flag => f }*) | optsParser(opts*)).*
      .map(_.flatten) ~ posArgParser(posArgs*)
  }.map {
    case (parsedOpts, parsedPosArgs) => parsedOpts ++ parsedPosArgs
  } <~ Space.* // allow an arbritary number of whitespaces arround arguments

  private def missingMsg(arg: Arg[?]): String = arg match {
    case posArg: PosArg.Single[?] => s"Missing required argument $posArg"
    case posArg: PosArg.Multi[?] => s"Missing required arguments $posArg"
    case flag: Flag => s"Missing required flag $flag"
    case argOpt: ArgOpt[?] => s"Missing required option $argOpt"
  }

  // parse the value of an argument passed by the user
  // arguments with a default value will be considered optional
  def valueParser[T](arg: Arg[T], parsed: Seq[ParsedArg[?]]): Parser[T] = {
    parsed.find(_.arg == arg)
      .map(_.value.asInstanceOf[T])
      .orElse(arg.default)
      .map(success(_))
      .getOrElse(failure(missingMsg(arg)))
  }

  /** $basedesc one argument.
    *
    * @example
    *   {{{
    * case class Options(fili: Boolean)
    * val parser: Parser[Options] = CLIParser(
    *   Flag('f', "fili"),
    * ).map(Options)
    *   }}}
    *
    * @see
    *   $posixlink
    */
  def apply[T](arg: Arg[T]): Parser[T] =
    for {
      parsedArgs <- argParser(arg)
      t <- valueParser(arg, parsedArgs)
    } yield t

  /** $basedesc two arguments.
    *
    * @example
    *   {{{
    * case class Options(fili: Boolean, kili: String)
    * val parser: Parser[Options] = CLIParser(
    *   Flag('f', "fili"),
    *   ArgOpt('k', "kili", StringBasic),
    * ).map(Options.tupled)
    *   }}}
    *
    * @see
    *   $posixlink
    */
  def apply[T1, T2](arg1: Arg[T1], arg2: Arg[T2]): Parser[(T1, T2)] =
    for {
      parsedArgs <- argParser(arg1, arg2)
      t1 <- valueParser(arg1, parsedArgs)
      t2 <- valueParser(arg2, parsedArgs)
    } yield (t1, t2)

  /** $basedesc three arguments.
    *
    * @example
    *   {{{
    * case class Options(fili: Boolean, kili: String, oin: Int)
    * val parser: Parser[Options] = CLIParser(
    *   Flag('f', "fili"),
    *   ArgOpt('k', "kili", StringBasic) withDefault "brother of fili",
    *   PosArg("oin", IntBasic),
    * ).map(Options.tupled)
    *   }}}
    *
    * @see
    *   $posixlink
    */
  def apply[T1, T2, T3](
    arg1: Arg[T1],
    arg2: Arg[T2],
    arg3: Arg[T3],
  ): Parser[(T1, T2, T3)] =
    for {
      parsedArgs <- argParser(arg1, arg2, arg3)
      t1 <- valueParser(arg1, parsedArgs)
      t2 <- valueParser(arg2, parsedArgs)
      t3 <- valueParser(arg3, parsedArgs)
    } yield (t1, t2, t3)

  /** $basedesc four arguments.
    *
    * @example
    *   {{{
    * case class Options(fili: Boolean, kili: String, oin: Int, gloin: Boolean)
    * val parser: Parser[Options] = CLIParser(
    *   Flag('f', "fili"),
    *   ArgOpt('k', "kili", StringBasic) withDefault "brother of fili",
    *   PosArg("oin", IntBasic),
    *   Flag('g', "gloin"),
    * ).map(Options.tupled)
    *   }}}
    *
    * @see
    *   $posixlink
    */
  def apply[T1, T2, T3, T4](
    arg1: Arg[T1],
    arg2: Arg[T2],
    arg3: Arg[T3],
    arg4: Arg[T4],
  ): Parser[(T1, T2, T3, T4)] =
    for {
      parsedArgs <- argParser(arg1, arg2, arg3, arg4)
      t1 <- valueParser(arg1, parsedArgs)
      t2 <- valueParser(arg2, parsedArgs)
      t3 <- valueParser(arg3, parsedArgs)
      t4 <- valueParser(arg4, parsedArgs)
    } yield (t1, t2, t3, t4)

  /** $basedesc five arguments.
    *
    * @example
    *   {{{
    * case class Options(fili: Boolean, kili: String, oin: Int, gloin: Boolean, balin: Char)
    * val parser: Parser[Options] = CLIParser(
    *   Flag('f', "fili"),
    *   ArgOpt('k', "kili", StringBasic) withDefault "brother of fili",
    *   PosArg("oin", IntBasic),
    *   Flag('g', "gloin"),
    *   ArgOpt('b', "balin", Letter),
    * ).map(Options.tupled)
    *   }}}
    *
    * @see
    *   $posixlink
    */
  def apply[T1, T2, T3, T4, T5](
    arg1: Arg[T1],
    arg2: Arg[T2],
    arg3: Arg[T3],
    arg4: Arg[T4],
    arg5: Arg[T5],
  ): Parser[(T1, T2, T3, T4, T5)] =
    for {
      parsedArgs <- argParser(arg1, arg2, arg3, arg4, arg5)
      t1 <- valueParser(arg1, parsedArgs)
      t2 <- valueParser(arg2, parsedArgs)
      t3 <- valueParser(arg3, parsedArgs)
      t4 <- valueParser(arg4, parsedArgs)
      t5 <- valueParser(arg5, parsedArgs)
    } yield (t1, t2, t3, t4, t5)

  /** $basedesc six arguments.
    *
    * @example
    *   {{{
    * case class Options(fili: Boolean, kili: String, oin: Int, gloin: Boolean, balin: Char, dwalin: Boolean)
    * val parser: Parser[Options] = CLIParser(
    *   Flag('f', "fili"),
    *   ArgOpt('k', "kili", StringBasic) withDefault "brother of fili",
    *   PosArg("oin", IntBasic),
    *   Flag('g', "gloin"),
    *   ArgOpt('b', "balin", Letter),
    *   Flag('d', "dwalin"),
    * ).map(Options.tupled)
    *   }}}
    *
    * @see
    *   $posixlink
    */
  def apply[T1, T2, T3, T4, T5, T6](
    arg1: Arg[T1],
    arg2: Arg[T2],
    arg3: Arg[T3],
    arg4: Arg[T4],
    arg5: Arg[T5],
    arg6: Arg[T6],
  ): Parser[(T1, T2, T3, T4, T5, T6)] =
    for {
      parsedArgs <- argParser(arg1, arg2, arg3, arg4, arg5, arg6)
      t1 <- valueParser(arg1, parsedArgs)
      t2 <- valueParser(arg2, parsedArgs)
      t3 <- valueParser(arg3, parsedArgs)
      t4 <- valueParser(arg4, parsedArgs)
      t5 <- valueParser(arg5, parsedArgs)
      t6 <- valueParser(arg6, parsedArgs)
    } yield (t1, t2, t3, t4, t5, t6)

  /** $basedesc seven arguments.
    *
    * @example
    *   {{{
    * case class Options(
    *   fili: Boolean,
    *   kili: String,
    *   oin: Int,
    *   gloin: Boolean,
    *   balin: Char,
    *   dwalin: Boolean,
    *   ori: Seq[Int],
    * )
    * val parser: Parser[Options] = CLIParser(
    *   Flag('f', "fili"),
    *   ArgOpt('k', "kili", StringBasic) withDefault "brother of fili",
    *   PosArg("oin", IntBasic),
    *   Flag('g', "gloin"),
    *   ArgOpt('b', "balin", Letter),
    *   Flag('d', "dwalin"),
    *   PosArg.Multi("ori", IntBasic) withDefault Seq(1, 2)
    * ).map(Options.tupled)
    *   }}}
    *
    * @see
    *   $posixlink
    */
  def apply[T1, T2, T3, T4, T5, T6, T7](
    arg1: Arg[T1],
    arg2: Arg[T2],
    arg3: Arg[T3],
    arg4: Arg[T4],
    arg5: Arg[T5],
    arg6: Arg[T6],
    arg7: Arg[T7],
  ): Parser[(T1, T2, T3, T4, T5, T6, T7)] =
    for {
      parsedArgs <- argParser(arg1, arg2, arg3, arg4, arg5, arg6, arg7)
      t1 <- valueParser(arg1, parsedArgs)
      t2 <- valueParser(arg2, parsedArgs)
      t3 <- valueParser(arg3, parsedArgs)
      t4 <- valueParser(arg4, parsedArgs)
      t5 <- valueParser(arg5, parsedArgs)
      t6 <- valueParser(arg6, parsedArgs)
      t7 <- valueParser(arg7, parsedArgs)
    } yield (t1, t2, t3, t4, t5, t6, t7)
}
