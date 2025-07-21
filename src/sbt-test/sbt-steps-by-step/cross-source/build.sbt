import sbtsteps.internal.*

enablePlugins(CIStepsPlugin)

ThisBuild / ci / steps := Seq(
  +(Compile / compile).continueOnError,
  +(Test / test),
)

val commonSettings = Seq(
  crossScalaVersions := Seq("2.12.18", "2.12.19", "2.13.12", "2.13.13")
)

val root = project.in(file(".")).settings(scalaVersion := "2.12.19")
val p01 = project.in(file("p01")).settings(commonSettings)
val p02 = project.in(file("p02")).settings(commonSettings)
val p03 = project.in(file("p03")).settings(commonSettings)
val p04 = project.in(file("p04")).settings(commonSettings)

val subZero = Def.mapScope(Scope.replaceThis(Global))
TaskKey[Unit]("ciStepsStatusSpec") := {
  val stateValue = state.value
  val rootRef = thisProjectRef.value
  val p01Ref = (p01 / thisProjectRef).value
  val p02Ref = (p02 / thisProjectRef).value
  val p03Ref = (p03 / thisProjectRef).value
  val p04Ref = (p04 / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByStep(
    (ci / InternalStepsKeys.pendingStepsByStep).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByStep](
    (
      +(Compile / compile).continueOnError,
      Seq(
        (
          p01Ref,
          Some(false),
          Seq(FailureMessage.Task(
            subZero(p01Ref / Compile / compileIncremental),
            "Compilation failed",
          )),
        ),
        // subsequent steps should be run, because of .continueOnError
        (p02Ref, Some(true), Nil),
        (p03Ref, Some(true), Nil),
        (p04Ref, Some(true), Nil),
        (rootRef, Some(true), Nil),
      ),
    ),
    (
      +(Test / test),
      Seq(
        (
          p01Ref,
          Some(false),
          Seq(FailureMessage.Task(
            subZero(p01Ref / Compile / compileIncremental),
            "Compilation failed",
          )),
        ),
        // subsequent cross versions should be aborted because p01 failed on 2.13
        // even though 2.12 has succeeded
        (p02Ref, None, Nil),
        (p03Ref, None, Nil),
        (p04Ref, None, Nil),
        // root succeeds, because it has only Scala 2.12
        // 2.12 source files are OK and the erroneous 2.13 file is only reached after
        (rootRef, Some(true), Nil),
      ),
    ),
  )
}
