import sbtsteps.internal.*

enablePlugins(CIStepsPlugin)

ThisBuild / ci / steps := Seq(
  +(Compile / compile),
)

/* This scripted test can be used to test the limits of sbt-steps w.r.t. memory usage.
 * By increasing the number of cross Scala versions the memory usage is increased as well.
 * The base setting below should just about work with a heap of 200M (default is 1G).
 * Since we cannot set the heap per scripted test, this needs to be adjusted manually.
 * To do so, change the -Xmx value in the scriptedLaunchOpts of the main build.sbt.
 * Another way to test this is by increasing the number of cross Scala versions below.
 *
 * WARNING: always set the heap and crossScalaVersions back to normal after testing!
 * This test also contains a regression test for cross building!
 */
val commonSettings = Seq(
  crossScalaVersions := (18 to 19).map(i => s"2.12.$i") ++ (12 to 14).map(i => s"2.13.$i"),
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
      +(Compile / compile),
      Seq(
        (
          p01Ref,
          Some(false),
          Seq(FailureMessage.Task(subZero(p01Ref / Compile / compileIncremental), "Compilation failed")),
        ),
        // should not be completed because p01 short-circuited the cross compilation
        (p02Ref, None, Nil),
        (p03Ref, None, Nil),
        (p04Ref, None, Nil),
        // root compile has succeeded, because it has only Scala 2.12
        // 2.12 source files are OK and the erroneous 2.13 file is only reached after
        // we consider this an acceptable side effect of the step grouping
        (rootRef, Some(true), Nil),
      ),
    ),
  )
}
