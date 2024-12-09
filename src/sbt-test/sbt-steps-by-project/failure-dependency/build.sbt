import sbtsteps.internal.*

Global / stepsGrouping := StepsGrouping.ByProject

lazy val root = project.in(file("."))
  .enablePlugins(CIStepsPlugin, TestkitPlugin)
  .settings(
    name := "root",
    // use a faulty Scala version to test the failure message
    scalaVersion := "2.12.100",
    ci / steps := Seq(
      (Compile / compile).continueOnError,
      Compile / compile,
      publish,
    ),
    // simplify list of resolvers so to make the test below easier
    Global / resolvers := Nil,
  )

TaskKey[Unit]("noPublishSpec") := {
  assertNotExists(baseDirectory.value / "ivy-repo" / "releases" / "default" / s"${name.value}_2.12" / version.value)
}

// use Task instead of must-mirror to test the CI report because we need to interpolate the ivy directory here
TaskKey[Unit]("ciStepsStatusReportSpec") := {
  val ivyDir = ivyPaths.value.ivyHome.get
  (ci / stepsStatusReport).toTask("").value shouldBe s"""<table><tr><td colspan=5 width=8000><b>root</b></td></tr><tr><td title="failed" width=40>:red_square:</td><td colspan=2><code>Compile / compile</code></td></tr><tr><td></td><td width=40>:x:</td><td>(<code>update</code>) sbt.librarymanagement.ResolveException: Error downloading org.scala-lang:scala-library:2.12.100<br />  Not found<br />  Not found<br />  not found: $ivyDir/local/org.scala-lang/scala-library/2.12.100/ivys/ivy.xml<br />  not found: https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.12.100/scala-library-2.12.100.pom</td></tr><tr><td title="failed" width=40>:red_square:</td><td colspan=2><code>Compile / compile</code></td></tr><tr><td></td><td width=40>:x:</td><td>(<code>update</code>) sbt.librarymanagement.ResolveException: Error downloading org.scala-lang:scala-library:2.12.100<br />  Not found<br />  Not found<br />  not found: $ivyDir/local/org.scala-lang/scala-library/2.12.100/ivys/ivy.xml<br />  not found: https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.12.100/scala-library-2.12.100.pom</td></tr><tr><td title="not completed" width=40>:white_large_square:</td><td colspan=2><code>publish</code></td></tr></table>"""
}

val subZero = Def.mapScope(Scope.replaceThis(Global))
TaskKey[Unit]("ciStepsStatusSpec") := {
  val rootRef = thisProjectRef.value
  val stateValue = state.value
  val ivyDir = ivyPaths.value.ivyHome.get
  val errorMsg =
    s"""sbt.librarymanagement.ResolveException: Error downloading org.scala-lang:scala-library:2.12.100
       |  Not found
       |  Not found
       |  not found: $ivyDir/local/org.scala-lang/scala-library/2.12.100/ivys/ivy.xml
       |  not found: https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.12.100/scala-library-2.12.100.pom""".stripMargin
  InternalStepsKeys.toTestTuplesByProject(
    (ci / InternalStepsKeys.pendingStepsByProject).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByProject](
    (
      rootRef,
      Seq(
        (
          (rootRef / Compile / compile).continueOnError,
          Some(false),
          Seq(FailureMessage.Task(subZero(rootRef / update), errorMsg))
        ),
        (
          rootRef / Compile / compile,
          Some(false),
          Seq(FailureMessage.Task(subZero(rootRef / update), errorMsg))
        ),
        (rootRef / publish, None, Nil),
      ),
    ),
  )
}
