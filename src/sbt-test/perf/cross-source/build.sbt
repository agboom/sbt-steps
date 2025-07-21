import sbtsteps.internal.*

enablePlugins(CIStepsPlugin)

ThisBuild / ci / steps := Seq(
  +(Compile / compile),
)

/* This scripted test can be used to test the limits of sbt-steps w.r.t. memory usage.
 * By increasing the number of cross Scala versions the memory usage is increased as well.
 * The base setting below should just about work with a heap of 150M (default is 1G).
 * Since we cannot set the heap per scripted test, this needs to be adjusted manually.
 * To do so, change the -Xmx value in the scriptedLaunchOpts of the main build.sbt.
 * Then run `sbt scripted perf/\*`
 * Another way to test this is by increasing the number of cross Scala versions below.
 */
val commonSettings = Seq(
  crossScalaVersions := (18 to 19).map(i => s"2.12.$i") ++ (12 to 14).map(i =>
    s"2.13.$i",
  ),
)

val root = project.in(file(".")).settings(scalaVersion := "2.12.19")
val p01 = project.in(file("p01")).settings(commonSettings)
val p02 = project.in(file("p02")).settings(commonSettings)
val p03 = project.in(file("p03")).settings(commonSettings)
val p04 = project.in(file("p04")).settings(commonSettings)
