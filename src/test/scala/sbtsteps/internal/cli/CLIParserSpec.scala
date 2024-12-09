package sbtsteps.internal.cli

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues

import sbt.complete.DefaultParsers.*
import sbt.*

class CLIParserSpec extends AnyWordSpec with Matchers with EitherValues {

  val fooFlag = Flag('f', "foo")
  val barFlag = Flag('b', "bar")
  val bazFlag = Flag('z', "baz")

  val filiOpt = ArgOpt('i', "fili", IntBasic)
  val kiliOpt = ArgOpt('k', "kili", StringBasic)
  val oinOpt = ArgOpt('o', "oin", Size)
  val gloinOpt = ArgOpt('g', "gloin", basicUri)

  val feeArg = PosArg("fee", IntBasic)
  val fiArg = PosArg("fi", Bool)
  val foArg = PosArg("fo", StringBasic)
  val fumArg = PosArg("fum", Size)

  val listArg = PosArg.Multi("nums", IntBasic)

  val objectListArg = PosArg.Multi("objs", StringBasic.map(Some(_)))

  val flagParser = CLIParser(fooFlag)

  val optionalFlagParser = CLIParser(fooFlag)

  val multiFlagParser = CLIParser(fooFlag, barFlag, bazFlag)

  val optParser = CLIParser(filiOpt)

  val multiOptParser = CLIParser(filiOpt, kiliOpt withDefault "kili", oinOpt, gloinOpt)

  val argParser = CLIParser(feeArg)

  val multiArgParser = CLIParser(feeArg, fiArg, foArg withDefault "fo", fumArg)

  val listArgParser = CLIParser(listArg)

  val listArgParserWithDefault = CLIParser(listArg withDefault Seq(1, 2))

  val combinedParser1 = CLIParser(fooFlag, barFlag, filiOpt, kiliOpt, feeArg, fiArg, listArg)

  val combinedParser2 = CLIParser(foArg withDefault "fo", bazFlag, fumArg withDefault 16, oinOpt, gloinOpt)

  val combinedParserWithObjectList = CLIParser(fooFlag, filiOpt, objectListArg)

  "flag option parsers" should {
    "accept a single flag" in {
      parse(" -f", flagParser).value shouldBe true
      parse(" -ftrue", flagParser).value shouldBe true
      parse(" -ffalse", flagParser).value shouldBe false
      parse(" -f true", flagParser).value shouldBe true
      parse(" -f false", flagParser).value shouldBe false

      parse(" --foo", flagParser).value shouldBe true
      parse(" --foo=false", flagParser).value shouldBe false
      parse(" --foo true", flagParser).value shouldBe true
    }

    "reject '=' in short notation" in {
      parse(" -f=false", flagParser).left.value shouldBe """Expected character in 'f'
                                                           |Expected whitespace character
                                                           |Expected 'true'
                                                           |Expected 'false'
                                                           |Expected end of input.
                                                           | -f=false
                                                           |   ^""".stripMargin
    }

    "reject input without leading whitespace" in {
      parse("-f", flagParser).left.value shouldBe """Expected whitespace character
                                                    |Expected end of input.
                                                    |-f
                                                    |^""".stripMargin
    }

    "reject invalid input" in {
      parse(" invalid", flagParser).left.value shouldBe """Expected whitespace character
                                                          |Expected '-'
                                                          |Expected '-f'
                                                          |Expected '--foo'
                                                          |Expected end of input.
                                                          | invalid
                                                          | ^""".stripMargin
    }

    "allow multiple flags to be combined in short notation" in {
      parse(" -fbz", multiFlagParser).value shouldBe ((true, true, true))
      parse(" -zbf", multiFlagParser).value shouldBe ((true, true, true))
      parse(" -bzf", multiFlagParser).value shouldBe ((true, true, true))
    }

    "allow multiple combinations of flags in short notation" in {
      parse(" -fb -z", multiFlagParser).value shouldBe ((true, true, true))
      parse(" -f -zb", multiFlagParser).value shouldBe ((true, true, true))
    }

    "allow the same flag to be repeated" in {
      parse(" -f -b --foo --bar --baz -zb", multiFlagParser).value shouldBe ((true, true, true))
    }

    "be false if omitted" in {
      parse("", flagParser).value shouldBe false

      parse(" -f -b", multiFlagParser).value shouldBe ((true, true, false))
      parse(" -fb", multiFlagParser).value shouldBe ((true, true, false))
      parse(" --foo --bar", multiFlagParser).value shouldBe ((true, true, false))

      parse(" -f -z", multiFlagParser).value shouldBe ((true, false, true))
      parse(" -fz", multiFlagParser).value shouldBe ((true, false, true))
      parse(" --foo --baz", multiFlagParser).value shouldBe ((true, false, true))
    }
  }

  "argument option parsers" should {
    "accept a single option with an input argument" in {
      parse(" --fili=1234", optParser).value shouldBe 1234
      parse(" -i 1234", optParser).value shouldBe 1234
      parse(" -i1234", optParser).value shouldBe 1234
    }

    "reject '=' in short notation" in {
      parse(" -i=1234", optParser).left.value shouldBe """Expected whitespace character
                                                         |Expected '-'
                                                         |Expected digit
                                                         | -i=1234
                                                         |   ^""".stripMargin
    }

    "reject missing or invalid option input" in {
      parse(" --fili", optParser).left.value shouldBe """Expected whitespace character
                                                        |Expected '='
                                                        | --fili
                                                        |       ^""".stripMargin
      parse(" --fili invalid", optParser).left.value shouldBe """Expected whitespace character
                                                                |Expected '-'
                                                                |Expected digit
                                                                | --fili invalid
                                                                |        ^""".stripMargin
    }

    "accept multiple options in arbritary order" in {
      val expected = (1234, "hello", 2147483648L, sbt.uri("file://test"))
      parse(" -i1234 -khello -o2G -gfile://test", multiOptParser).value shouldBe expected
      parse(" -o 2G -i 1234 -gfile://test -k hello", multiOptParser).value shouldBe expected
      parse(" -khello -i 1234 -g file://test -o 2G", multiOptParser).value shouldBe expected
    }

    "allow for defaults" in {
      val expected = (1234, "kili", 2147483648L, sbt.uri("file://test"))
      parse(" -i 1234 -g file://test -o 2G", multiOptParser).value shouldBe expected
    }

    "allow the same option to be repeated, taking the first occurrence" in {
      parse(" -i1 -i2", optParser).value shouldBe 1
      parse(" --fili=1 --fili 2", optParser).value shouldBe 1
      parse(" -i 1 --fili 2", optParser).value shouldBe 1
      parse(" --fili 1 -i 2", optParser).value shouldBe 1

      val expected = (2, "kili", 1, sbt.uri("file://one"))
      parse(" -i2 -o1B -gfile://one -i3 -o2G -gfile://two", multiOptParser).value shouldBe expected
      parse(" -i2 -o1B -gfile://one --fili=3 --oin 2G --gloin=file://two", multiOptParser).value shouldBe expected
    }
  }

  "any option parser" should {
    "throw an error on ambiguous short or long notations" in {
      val err1 = intercept[RuntimeException](CLIParser(Flag('f', "foo"), ArgOpt('f', "fili", StringBasic)))
      err1.getMessage shouldBe "Ambiguous notation in -f|--foo and -f|--fili"

      val err2 = intercept[RuntimeException](CLIParser(ArgOpt('f', "foo", StringBasic), Flag('i', "foo")))
      err2.getMessage shouldBe "Ambiguous notation in -f|--foo and -i|--foo"
    }
  }

  "single positional argument parsers" should {
    "accept a single argument" in {
      parse(" 1234", argParser).value shouldBe 1234
    }

    "accept multiple arguments" in {
      parse(" 1 true s 2B", multiArgParser).value shouldBe ((1, true, "s", 2))
    }

    "reject the wrong order of arguments" in {
      parse(" true 1 s 2B", multiArgParser).left.value shouldBe """Expected whitespace character
                                                                  |Expected '-'
                                                                  |Expected digit
                                                                  | true 1 s 2B
                                                                  | ^""".stripMargin
    }

    "reject missing, invalid or repeated arguments" in {
      parse(" 1 true s", multiArgParser).left.value shouldBe """Expected whitespace character
                                                               | 1 true s
                                                               |         ^""".stripMargin

      parse(" invalid true s 2B", multiArgParser).left.value shouldBe """Expected whitespace character
                                                                        |Expected '-'
                                                                        |Expected digit
                                                                        | invalid true s 2B
                                                                        | ^""".stripMargin
      parse(" true true true true", multiArgParser).left.value shouldBe """Expected whitespace character
                                                                          |Expected '-'
                                                                          |Expected digit
                                                                          | true true true true
                                                                          | ^""".stripMargin
    }

    "allow for defaults" in {
      parse(" 1 true 2B", multiArgParser).value shouldBe ((1, true, "fo", 2))
    }
  }

  "positional list argument parsers" should {
    "accept a single argument" in {
      parse(" 1", listArgParser).value shouldBe Seq(1)
    }

    "accept multiple arguments" in {
      parse(" 1 2", listArgParser).value shouldBe Seq(1, 2)
    }

    "accept an arbritary number of whitespaces around arguments" in {
      parse(" 1   2   ", listArgParser).value shouldBe Seq(1, 2)
    }

    "accept empty input without leading space" in {
      parse("", listArgParser).value shouldBe Nil
    }

    "reject invalid input" in {
      parse(" invalid 1", listArgParser).left.value shouldBe """Expected whitespace character
                                                               |Expected '-'
                                                               |Expected digit
                                                               |Expected whitespace before start of argument list
                                                               |Expected end of input.
                                                               | invalid 1
                                                               | ^""".stripMargin
    }

    "reject non-empty argument list without a leading space" in {
      parse("1", listArgParser).left.value shouldBe """Expected whitespace before start of argument list
                                                      |1
                                                      | ^""".stripMargin
    }

    "allow for defaults" in {
      parse("", listArgParserWithDefault).value shouldBe Seq(1, 2)
      parse(" ", listArgParserWithDefault).value shouldBe Seq(1, 2)
    }
  }

  "a combination of flag-, option- and argument parsers" should {
    "accept valid arguments" in {
      parse(" -f -b -i1 -kx 2 false", combinedParser1).value shouldBe ((
        true,
        true,
        1,
        "x",
        2,
        false,
        Nil,
      ))
    }

    "allow a parser that maps to objects and not go out of memory" in {
      parse(" --foo -i 1 obj1 obj2 obj3 obj4 obj5", combinedParserWithObjectList).value shouldBe((
        true,
        1,
        Seq("obj1".some, "obj2".some, "obj3".some, "obj4".some, "obj5".some)
      ))
    }


    "accept an arbritary number of whitespace characters around arguments" in {
      parse("   -f    -b           -i1   -kx    2     false           1 2", combinedParser1).value shouldBe ((
        true,
        true,
        1,
        "x",
        2,
        false,
        Seq(1, 2),
      ))
    }

    "always parse positional arguments last, but pass them in the original order" in {
      parse(" -z --oin=2B --gloin file://test hello 1K", combinedParser2).value shouldBe ((
        "hello",
        true,
        1024,
        2,
        sbt.uri("file://test"),
      ))
    }

    "set flags to false if omitted" in {
      parse(" -i1 -ks 2 true", combinedParser1).value shouldBe ((
        false,
        false,
        1,
        "s",
        2,
        true,
        Nil,
      ))
    }

    "allow for defaults" in {
      parse(" -z --oin=2B --gloin file://test", combinedParser2).value shouldBe ((
        "fo",
        true,
        16,
        2,
        sbt.uri("file://test"),
      ))
    }

    "reject the wrong order of positional arguments" in {
      parse(" -i1 -ks 2 1 2 true", combinedParser1).left.value shouldBe """Expected whitespace character
                                                                          |Expected 'true'
                                                                          |Expected 'false'
                                                                          | -i1 -ks 2 1 2 true
                                                                          |           ^""".stripMargin
      parse(" -z --oin=2B --gloin file://test 1K hello", combinedParser2).left
        .value shouldBe """Expected whitespace character
                          |Expected digit
                          |Expected end of input.
                          | -z --oin=2B --gloin file://test 1K hello
                          |                                    ^""".stripMargin
      parse(" -f -b -i1 -kx false 2", combinedParser1).left.value shouldBe """Expected whitespace character
                                                                             |Expected '-k'
                                                                             |Expected '--kili'
                                                                             |Expected '-'
                                                                             |Expected '-f'
                                                                             |Expected '--foo'
                                                                             |Expected '-b'
                                                                             |Expected '--bar'
                                                                             |Expected '-i'
                                                                             |Expected '--fili'
                                                                             |Expected digit
                                                                             | -f -b -i1 -kx false 2
                                                                             |               ^""".stripMargin
    }
  }
}
