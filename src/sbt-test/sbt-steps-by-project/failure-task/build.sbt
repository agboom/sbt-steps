import sbtsteps.internal.*

enablePlugins(CIStepsPlugin, TestkitPlugin)

Global / stepsGrouping := StepsGrouping.ByProject

inThisBuild(Seq(
  scalaVersion := "2.12.18",
  ci / steps := Seq(
    // test continue-on-error functionality
    (Test / test).continueOnError,
  ),
))

lazy val foo = project.in(file("foo"))
  .enablePlugins(CIStepsPlugin)
  .dependsOn(bar)
  .settings(
    ci / steps ++= Seq(
      publish,
      Compile / compile,
    ),
  )

lazy val bar = project.in(file("bar"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    ci / steps ++= Seq(
      Compile / compile,
    ),
  )

lazy val root = project.in(file("."))

val subZero = Def.mapScope(Scope.replaceThis(Global))
TaskKey[Unit]("ciStepsStatusSpec") := {
  val rootRef = thisProjectRef.value
  val barRef = (bar / thisProjectRef).value
  val fooRef = (foo / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByProject(
    (ci / InternalStepsKeys.pendingStepsByProject).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByProject](
    (
      barRef,
      Seq(
        ((barRef / Test / test).continueOnError, Some(true), Nil),
        (barRef / Compile / compile, Some(true), Nil),
      ),
    ),
    (
      fooRef,
      Seq(
        (
          (fooRef / Test / test).continueOnError,
          Some(false),
          Seq(
            FailureMessage.Task(subZero(fooRef / Compile / compileIncremental), "Compilation failed"),
          ),
        ),
        (
          fooRef / publish,
          Some(false),
          Seq(
            FailureMessage.Task(subZero(fooRef / Compile / compileIncremental), "Compilation failed"),
            FailureMessage.Task(subZero(fooRef / Compile / doc), "Scaladoc generation failed"),
          ),
        ),
        (fooRef / Compile / compile, None, Nil),
      ),
    ),
    (
      rootRef,
      Seq(
        ((rootRef / Test / test).continueOnError, None, Nil),
      ),
    ),
  )
}
