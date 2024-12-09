import sbtsteps.internal.*

Global / stepsGrouping := StepsGrouping.ByProject

lazy val root = project.in(file("."))
  .enablePlugins(CIStepsPlugin, TestkitPlugin)
  .settings(
    name := "root",
    scalaVersion := "2.12.18",
    ci / steps := Seq(
      // test input task step with invalid input
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
  InternalStepsKeys.toTestTuplesByProject(
    (ci / InternalStepsKeys.pendingStepsByProject).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByProject](
    (
      rootRef,
      Seq(
        (
          rootRef / ci / stepsTree withInput "invalid",
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
        (rootRef / publish, None, Nil),
      ),
    ),
  )
}
