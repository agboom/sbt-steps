import sbtsteps.internal.*

enablePlugins(CIStepsPlugin, TestkitPlugin)

ThisBuild / scalaVersion := "2.12.18"

lazy val foo = (project in file("foo"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    name := "foo",
    ci / steps := Seq(
      Test / test,
    ),
  )

lazy val bar = (project in file("bar"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    name := "bar",
    ci / steps := Seq(
      "compileCommand".continueOnError,
      "compileCommand",
    ),
  ).dependsOn(foo)

lazy val root = project.in(file("."))
  .settings(
    name := "root",
    publishArtifact := false,
    ci / steps := Seq(
      "compileCommand".continueOnError,
      Test / test,
    ),
  ).aggregate(foo, bar)

// simulate (aggregated) command step with an alias
addCommandAlias("compileCommand", "compile")

val subZero = Def.mapScope(Scope.replaceThis(Global))
TaskKey[Unit]("ciStepsStatusSpec") := {
  val stateValue = state.value
  val rootRef = thisProjectRef.value
  val fooRef = (foo / thisProjectRef).value
  val barRef = (bar / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByStep(
    (ci / InternalStepsKeys.pendingStepsByStep).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByStep](
    (
      "compileCommand".continueOnError,
      Seq(
        (
          barRef,
          Some(false),
          Seq(FailureMessage.Command(Exec("compile", None), "command failed")),
        ),
        (
          rootRef,
          Some(false),
          Seq(FailureMessage.Command(Exec("compile", None), "command failed")),
        ),
      ),
    ),
    (
      "compileCommand",
      Seq(
        (
          barRef,
          Some(false),
          Seq(FailureMessage.Command(Exec("compile", None), "command failed")),
        ),
      ),
    ),
    (
      Test / test,
      Seq(
        (fooRef, None, Nil),
        (rootRef, None, Nil),
      ),
    ),
  ),
}
