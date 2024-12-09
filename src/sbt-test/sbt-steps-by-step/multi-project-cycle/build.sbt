import sbtsteps.internal.*
import scala.util.Try

enablePlugins(CIStepsPlugin, TestkitPlugin)

ThisBuild / scalaVersion := "2.13.14"

lazy val foo = (project in file("foo"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    ci / steps := Seq(
      compile,
      Test / test,
      publish,
    ),
  )

lazy val root = project.in(file("."))
  .settings(
    name := "root",
    ci / steps := Seq(
      publish,
      compile,
    ),
  ).aggregate(foo)

TaskKey[Unit]("cycleSpec", "") := {
  val expectedExceptionMsg =
    """One or more steps are configured in a way that causes a cycle in the aggregated steps:
      |- task publish in project foo
      |- task publish in project root""".stripMargin
  val result = (ci / InternalStepsKeys.pendingStepsByStep).failure.value.directCause.map(_.toString) 
  result shouldBe Some(expectedExceptionMsg)
}
