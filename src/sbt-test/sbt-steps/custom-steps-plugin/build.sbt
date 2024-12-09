/** This test shows that two steps plugins can co-exist.
  * They have separate steps settings with separate result stati.
  */
import sbtsteps.internal.*

Global / ci / stepsGrouping := StepsGrouping.ByProject

lazy val root = project.in(file("."))
  .enablePlugins(CIStepsPlugin, CustomStepsPlugin, TestkitPlugin)
  .settings(
    name := "root",
    scalaVersion := "2.12.18",
    ci / steps := Seq(
      Test / test,
      publish,
    ),
    custom / steps := Seq(
      clean,
      Compile / compile,
      customTask,
    ),
  )

TaskKey[Unit]("ciSpec") := {
  (Global / ci / stepsGrouping).value shouldBe StepsGrouping.ByProject
  (Global / stepsGrouping).value shouldBe StepsGrouping.ByStep
}

TaskKey[Unit]("customSpec") := {
  // default value should be used (delegated to Global / ciGroupByStep)
  (Global / custom / stepsGrouping).value shouldBe StepsGrouping.ByStep
}

TaskKey[Unit]("afterCiSpec") := {
  val rootRef = thisProjectRef.value
  InternalStepsKeys.toTestTuplesByProject(
    (ci / InternalStepsKeys.pendingStepsByProject).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByProject](
    (
      rootRef,
      Seq(
        (
          rootRef / Test / test,
          Some(true),
          Nil,
        ),
        (
          rootRef / publish,
          Some(true),
          Seq(SuccessMessage.Published("root", "0.1.0-SNAPSHOT")),
        ),
      ),
    ),
  )

  // assert test step
  assertExists(target.value / s"scala-2.12" / "test-classes" / "Spec.class")
  // assert publish step
  assertExists(baseDirectory.value / "ivy-repo" / "releases" / name.value / s"${name.value}_2.12" / version.value)
}

TaskKey[Unit]("afterCustomSpec") := {
  val rootRef = thisProjectRef.value
  InternalStepsKeys.toTestTuplesByStep(
    (custom / InternalStepsKeys.pendingStepsByStep).value,
    (custom / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByStep](
    (
      clean,
      Seq(
        (
          rootRef,
          Some(true),
          Nil,
        ),
      ),
    ),
    (
      (Compile / compile),
      Seq(
        (
          rootRef,
          Some(true),
          Nil,
        ),
      ),
    ),
    (
      customTask,
      Seq(
        (
          rootRef,
          Some(true),
          Seq(CustomTaskSuccessMessage("done")),
        ),
      ),
    ),
  )

  // test classes should not exist, because custom did a clean compile (no test)
  assertNotExists(target.value / s"scala-2.12" / "test-classes" / "Spec.class")
}
