import sbtsteps.internal.*

enablePlugins(CIStepsPlugin)

lazy val expensive = taskKey[Unit]("")
lazy val failExpensive = settingKey[Boolean]("")

ThisBuild / failExpensive := false

/**
  * This test is to verify if task steps are run concurrently.
  */
lazy val projSet = Def.settings(
  expensive := {
    val projName = thisProjectRef.value.project
    streams.value.log.info(s"Starting expensive task for project $projName ...")
    Thread.sleep(10000)
    if (failExpensive.value) {
      throw new RuntimeException(s"Failed expensive task for project $projName")
    }
    streams.value.log.info(s"Succeeded expensive task for project $projName.")
  },
)

ThisBuild / ci / steps := Seq(
  expensive,
)

lazy val p01 = project.in(file("p01"))
  .settings(projSet)
lazy val p02 = project.in(file("p02"))
  .settings(projSet)
lazy val p03 = project.in(file("p03"))
  .settings(projSet)
lazy val p04 =
  project.in(file("p04"))
    .settings(projSet)
    .settings(failExpensive := true)
lazy val root =
  project.in(file("."))
    .settings(projSet)
    .aggregate(p01, p02, p03, p04)
