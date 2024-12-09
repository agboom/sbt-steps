import sbtsteps.internal.*

ThisBuild / scalaVersion := "2.12.18"

lazy val root = project.in(file("."))
  .enablePlugins(CIStepsPlugin, TestkitPlugin)
  .settings(
    name := "root",
    ci / steps := Seq(
      // check if invalid input is handled
      ci / stepsTree withInput "invalid",
      publish,
    ),
  )

TaskKey[Unit]("noPublishSpec") := {
  assertNotExists(baseDirectory.value / "ivy-repo" / "releases" / "default" / s"${name.value}_2.12" / version.value)
}

val subZero = Def.mapScope(Scope.replaceThis(Global))
TaskKey[Unit]("ciStepsStatusSpec") := {
  val rootRef = thisProjectRef.value
  val stateValue = state.value
  InternalStepsKeys.toTestTuplesByStep(
    (ci / InternalStepsKeys.pendingStepsByStep).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByStep](
    (
      ci / stepsTree withInput "invalid",
      Seq(
        (
          rootRef,
          Some(false),
          Seq(
            FailureMessage.Task(
              subZero(rootRef / ci / stepsTree),
              """Invalid programmatic input:
                |Expected whitespace character
                |Expected '-'
                |Expected '-v'
                |Expected '--verbose'
                |Expected '-s'
                |Expected '--status'
                |Expected end of input.
                | invalid
                | ^""".stripMargin,
            ),
          ),
        ),
      ),
    ),
    (publish, Seq((rootRef, None, Nil))),
  )
}
