import sbtsteps.internal.*

enablePlugins(CIStepsPlugin, TestkitPlugin)

lazy val counter = settingKey[Int]("").withRank(KeyRanks.Invisible)

Global / ci / stepsGrouping := StepsGrouping.ByProject

inThisBuild(Seq(
  scalaVersion := "2.12.18",
  crossScalaVersions := Seq("2.13.12"),
  counter := 0,

  // set ci / steps for entire build to test if this is picked up in subprojects
  ci / steps := Seq(
    "increment".once forProject LocalRootProject,
    // add run-once step without projectFilter to test deduplication
    "show counter".once,
    (Compile / compile) forProject bar,
    publish,
  ),
))

lazy val foo = (project in file("foo"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    // set project specific steps
    ci / steps := Seq(
      "increment".once forProject LocalRootProject,
      +(Test / test),
      +publish named "Cross publish foo",
      ci / stepsStatusReport withInput "-",
    ),
  )

lazy val bar = (project in file("bar"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    // test if `skip` value is correctly handled by ci
    publish / skip := true,
    // test input task step with project setting reference
    ci / steps += ci / stepsStatusReport withInput s"ci-reports/${name.value}.html",
  )
  .dependsOn(foo)
  .aggregate(foo) // to check if steps not run for aggregated projects

lazy val root = project.in(file("."))
  .settings(
    name := "root",
    // test if publishArtifact is correctly handled
    publishArtifact := false,
    // test input task step with project setting reference
    ci / steps += ci / stepsStatusReport withInput s"ci-reports/${name.value}.html",
  ).aggregate(foo, bar)

// simulate command step with an alias
addCommandAlias("increment", "set counter := counter.value + 1")

// to test if aggregate setting is honored in commands (see test script)
addCommandAlias("compileCommand", "compile")

TaskKey[Unit]("beforeCiSpec") := {
  val rootRef = thisProjectRef.value
  val barRef = (bar / thisProjectRef).value
  val fooRef = (foo / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByProject(
    (ci / InternalStepsKeys.pendingStepsByProject).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByProject](
    (
      barRef,
      Seq(
        (
          "increment".once forProject LocalRootProject,
          None,
          Seq(SkippedMessage.ProjectFilter(LocalRootProject, barRef)),
        ),
        ("show counter".once, None, Nil),
        ((barRef / Compile / compile) forProject bar, None, Nil),
        (
          barRef / publish,
          None,
          Seq(SkippedMessage.SkipTrue(barRef / publish / skip)),
        ),
        (barRef / ci / stepsStatusReport withInput "ci-reports/bar.html", None, Nil),
      ),
    ),
    (
      fooRef,
      Seq(
        (
          "increment".once forProject LocalRootProject,
          None,
          Seq(SkippedMessage.ProjectFilter(LocalRootProject, fooRef)),
        ),
        (+(fooRef / Test / test), None, Nil),
        (+(fooRef / publish) named "Cross publish foo", None, Nil),
        (fooRef / ci / stepsStatusReport withInput "-", None, Nil),
      ),
    ),
    (
      rootRef,
      Seq(
        ("increment".once forProject LocalRootProject, None, Nil),
        ("show counter".once, None, Seq(SkippedMessage.RunOnce)),
        (
          (Compile / compile) forProject bar,
          None,
          Seq(SkippedMessage.ProjectFilter(bar, rootRef)),
        ),
        (rootRef / publish, None, Nil),
        (rootRef / ci / stepsStatusReport withInput "ci-reports/root.html", None, Nil),
      ),
    ),
  )
}

TaskKey[Unit]("afterCiSpec") := {
  val rootRef = thisProjectRef.value
  val barRef = (bar / thisProjectRef).value
  val fooRef = (foo / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByProject(
    (ci / InternalStepsKeys.pendingStepsByProject).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByProject](
    (
      barRef,
      Seq(
        (
          "increment".once forProject LocalRootProject,
          Some(true),
          Seq(SkippedMessage.ProjectFilter(LocalRootProject, barRef)),
        ),
        ("show counter".once, Some(true), Nil),
        ((barRef / Compile / compile) forProject bar, Some(true), Nil),
        (
          barRef / publish,
          Some(true),
          Seq(SkippedMessage.SkipTrue(barRef / publish / skip)),
        ),
        (
          barRef / ci / stepsStatusReport withInput "ci-reports/bar.html",
          Some(true),
          Nil,
        ),
      ),
    ),
    (
      fooRef,
      Seq(
        (
          "increment".once forProject LocalRootProject,
          Some(true),
          Seq(SkippedMessage.ProjectFilter(LocalRootProject, fooRef)),
        ),
        (+(fooRef / Test / test), Some(true), Nil),
        (
          +(fooRef / publish) named "Cross publish foo",
          Some(true),
          Seq(SuccessMessage.Published("foo", "0.1.0-SNAPSHOT")),
        ),
        (fooRef / ci / stepsStatusReport withInput "-", Some(true), Nil),
      ),
    ),
    (
      rootRef,
      Seq(
        ("increment".once forProject LocalRootProject, Some(true), Nil),
        ("show counter".once, Some(true), Seq(SkippedMessage.RunOnce)),
        (
          (Compile / compile) forProject bar,
          Some(true),
          Seq(SkippedMessage.ProjectFilter(bar, rootRef)),
        ),
        (rootRef / publish, Some(true), Nil),
        (
          rootRef / ci / stepsStatusReport withInput "ci-reports/root.html",
          Some(true),
          Nil,
        ),
      ),
    ),
  )
}

TaskKey[Unit](
  "runOnceCommandSpec",
  "\"increment\".once should be run only once for the entire build",
) := {
  counter.value shouldBe 1
}

TaskKey[Unit]("rootSpec", "Steps for root project should run correctly") := {
  // published jars should not exist, because publishArtifact := false
  assertNotExists(
    baseDirectory
      .value / "ivy-repo" / "releases" / s"$name.value}" / s"${name.value}_2.12" / version.value,
  )
  assertNotExists(
    baseDirectory
      .value / "ivy-repo" / "releases" / s"$name.value}" / s"${name.value}_2.13" / version.value,
  )
}

TaskKey[Unit]("fooSpec", "Steps for foo project should run correctly") := {
  // NOTE: all steps are run with cross build enabled, so crossScalaVersions is used, not scalaVersion
  // main classes for 2.13 should exist, because test task depends on main
  assertExists((foo / target).value / s"scala-2.13" / "classes" / "Foo.class")
  // test classes for 2.13 should exist, due to cross compile
  assertExists(
    (foo / target).value / s"scala-2.13" / "test-classes" / "FooSpec.class",
  )
  // main classes for 2.12 should exist, because bar depends on them
  assertExists((foo / target).value / s"scala-2.12" / "classes" / "Foo.class")
  // test classes for 2.12 should not exist
  assertNotExists(
    (foo / target).value / s"scala-2.12" / "test-classes" / "FooSpec.class",
  )
  // published jar for 2.13 should exist
  assertExists(
    baseDirectory
      .value / "ivy-repo" / "releases" / s"${(foo / name).value}" / s"${(foo / name).value}_2.13" / version.value,
  )
  // published jar for 2.12 should not exist
  assertNotExists(
    baseDirectory
      .value / "ivy-repo" / "releases" / s"${(foo / name).value}" / s"${(foo / name).value}_2.12" / version.value,
  )
}

TaskKey[Unit]("barSpec", "Steps for bar project should run correctly") := {
  // NOTE: all steps are run with cross build disabled, so scalaVersion is used
  // main classes for 2.12 should exist
  assertExists((bar / target).value / s"scala-2.12" / "classes" / "Bar.class")
  // main classes for 2.13 should not exist
  assertNotExists(
    (bar / target).value / s"scala-2.13" / "classes" / "Bar.class",
  )
  // test classes should not exist
  assertNotExists(
    (bar / target).value / s"scala-2.12" / "test-classes" / "BarSpec.class",
  )
  assertNotExists(
    (bar / target).value / s"scala-2.13" / "test-classes" / "BarSpec.class",
  )
  // published jar should not exist
  assertNotExists(
    baseDirectory
      .value / "ivy-repo" / "releases" / s"${(bar / name).value}" / s"${(bar / name).value}_2.12" / version.value,
  )
  assertNotExists(
    baseDirectory
      .value / "ivy-repo" / "releases" / s"${(bar / name).value}" / s"${(bar / name).value}_2.13" / version.value,
  )
}

TaskKey[Unit](
  "resetScalaVersionSpec",
  "After cross build step scalaVersion should be reset to original",
) := {
  scalaVersion.value shouldBe "2.12.18"
}

TaskKey[Unit](
  "aggregateCommandSpec",
  "Aggregate setting should be honored in commands",
) := {
  assertExists(target.value / s"scala-2.12" / "classes" / "Root.class")
  assertExists((foo / target).value / s"scala-2.12" / "classes" / "Foo.class")
  assertExists((bar / target).value / s"scala-2.12" / "classes" / "Bar.class")
}
