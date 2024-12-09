import sbtsteps.internal.*

enablePlugins(CIStepsPlugin, TestkitPlugin)

inThisBuild(Seq(
  scalaVersion := "2.12.18",
  ci / steps := Seq(
    // test continue-on-error functionality
    (Test / test).continueOnError,
  ),
))

lazy val foo = project.in(file("foo"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    ci / steps ++= Seq(
      publish,
      Compile / compile,
    ),
  )

lazy val bar = project.in(file("bar"))
  .enablePlugins(CIStepsPlugin)
  .dependsOn(foo)

lazy val root = project.in(file("."))

val subZero = Def.mapScope(Scope.replaceThis(Global))
TaskKey[Unit]("ciStepsStatusSpec") := {
  val stateValue = state.value
  val rootRef = thisProjectRef.value
  val barRef = (bar / thisProjectRef).value
  val fooRef = (foo / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByStep(
    (ci / InternalStepsKeys.pendingStepsByStep).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByStep](
    (
      (Test / test).continueOnError,
      Seq(
        (
          barRef,
          Some(false),
          // foo task in error because bar depends on foo
          Seq(FailureMessage.Task(subZero(fooRef / Compile / compileIncremental), "Compilation failed")),
        ),
        (
          fooRef,
          Some(false),
          Seq(FailureMessage.Task(subZero(fooRef / Compile / compileIncremental), "Compilation failed")),
        ),
        (rootRef, Some(true), Nil),
      ),
    ),
    (
      publish,
      Seq(
        (
          fooRef,
          Some(false),
          Seq(
            FailureMessage.Task(subZero(fooRef / Compile / compileIncremental), "Compilation failed"),
            FailureMessage.Task(subZero(fooRef / Compile / doc), "Scaladoc generation failed"),
          ),
        ),
      ),
    ),
    (
      (Compile / compile),
      Seq((fooRef, None, Nil)),
    ),
  )
}
