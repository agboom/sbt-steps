import sbtsteps.internal.*

enablePlugins(CIStepsPlugin, TestkitPlugin)

Global / ci / stepsGrouping := StepsGrouping.ByProject

inThisBuild(Seq(
  scalaVersion := "2.12.18",
  ci / steps := Nil,
))

lazy val foo = (project in file("foo"))
  .enablePlugins(CIStepsPlugin)

lazy val bar = (project in file("bar"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    name := "bar",
    ci / steps := Seq(
      "compileCommand".continueOnError,
    ),
  ).dependsOn(foo)

lazy val root = project.in(file("."))
  .settings(
    name := "root",
    publishArtifact := false,
    ci / steps := Seq(
      "compileCommand",
      Test / test,
    ),
  ).aggregate(foo, bar)

// simulate (aggregated) command step with an alias
addCommandAlias("compileCommand", "compile")

TaskKey[Unit]("ciStepsStatusSpec") := {
  val stateValue = state.value
  val rootRef = thisProjectRef.value
  val fooRef = (foo / thisProjectRef).value
  val barRef = (bar / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByProject(
    (ci / InternalStepsKeys.pendingStepsByProject).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByProject](
    (
      barRef,
      Seq(
        (
          "compileCommand".continueOnError,
          Some(false),
          Seq(FailureMessage.Command(Exec("compile", None), "command failed")),
        ),
      ),
    ),
    (fooRef, Nil),
    (
      rootRef,
      Seq(
        (
          "compileCommand",
          Some(false),
          Seq(FailureMessage.Command(Exec("compile", None), "command failed")),
        ),
        (rootRef / Test / test, None, Nil),
      ),
    ),
  )
}
