
Global / stepsGrouping := StepsGrouping.ByProject

lazy val root = project.in(file("."))
  .enablePlugins(CIStepsPlugin, TestkitPlugin)
  .settings(
    name := "root",
    scalaVersion := "2.12.18",
    crossScalaVersions := Seq("2.12.18", "2.13.12"),
    ci / steps := Seq(
      // test if inputTask works properly
      +(Test / testOnly) withInput "*Spec",
      +publish,
    ),
  )

TaskKey[Unit]("afterCiSpec") := {
  // assert test step
  assertExists(target.value / s"scala-2.12" / "test-classes" / "Spec.class")
  assertExists(target.value / s"scala-2.13" / "test-classes" / "Spec.class")
  // assert publish step
  assertExists(baseDirectory.value / "ivy-repo" / "releases" / name.value / s"${name.value}_2.12" / version.value)
  assertExists(baseDirectory.value / "ivy-repo" / "releases" / name.value / s"${name.value}_2.13" / version.value)
}
