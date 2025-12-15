import sbtsteps.internal.*

enablePlugins(CIStepsPlugin, TestkitPlugin)

// simulate different step with a different config scope, e.g. Docker / publish
lazy val Docker = config("docker")

lazy val counter = settingKey[Int]("").withRank(KeyRanks.Invisible)

// simulate command step with an alias
addCommandAlias("increment", "set counter := counter.value + 1")
addCommandAlias("decrement", "set counter := counter.value - 1")

// to test if aggregate setting is honored in commands (see test script)
addCommandAlias("compileCommand", "compile")

inThisBuild(Seq(
  counter := 0,
  scalaVersion := "2.13.16",
  crossScalaVersions := Seq("2.12.20", "2.13.16"),

  // set ci / steps for entire build to test if this is picked up in subprojects
  ci / steps := Seq(
    "increment".once forProject LocalRootProject,
    // add command in subproject to test if project is set correctly
    "decrement" forProject foo,
    // add runOnce step without projectFilter to test deduplication
    "show counter".once,
    Test / test,
    +publish named "Cross publish",
  ),
))

lazy val foo = (project in file("foo"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    // test input task step with project setting reference
    ci / steps += ci / stepsStatusReport withInput s"ci-reports/${name.value}.html",
  )

lazy val bar = (project in file("bar"))
  .enablePlugins(CIStepsPlugin)
  .settings(
    // set project specific steps
    ci / steps := Seq(
      // add another step before "increment" to see if the steps are sorted correctly
      "show scalaVersion",
      "increment".once forProject LocalRootProject,
      +(Test / test),
      Docker / publish,
      ci / stepsStatusReport withInput "-",
    ),
  )
  .dependsOn(foo)
  .aggregate(foo) // to check agrregate behavior (yes for commands, no for tasks)

lazy val root = (project in file("."))
  .settings(
    name := "root",
    // this setting does not create a SkippedStep, but it does inhibit publishing
    publishArtifact := false,
    // set different scalaVersion to see if per-project versions are honored
    // note that this will also overwrite crossScalaVersions for this project!
    scalaVersion := "2.12.18",
    // test input task step with project setting reference
    ci / steps += ci / stepsStatusReport withInput s"ci-reports/${name.value}.html",
  ).aggregate(foo, bar)

TaskKey[Unit]("beforeCiSpec") := {
  val rootRef = thisProjectRef.value
  val barRef = (bar / thisProjectRef).value
  val fooRef = (foo / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByStep(
    (ci / InternalStepsKeys.pendingStepsByStep).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByStep](
    (
      "show scalaVersion",
      Seq((barRef, None, Nil)),
    ),
    (
      "increment".once forProject LocalRootProject,
      Seq(
        (
          barRef,
          None,
          Seq(SkippedMessage.ProjectFilter(LocalRootProject, barRef)),
        ),
        (
          fooRef,
          None,
          Seq(SkippedMessage.ProjectFilter(LocalRootProject, fooRef)),
        ),
        (rootRef, None, Nil),
      ),
    ),
    (
      +(Test / test),
      Seq((barRef, None, Nil)),
    ),
    (
      Docker / publish,
      Seq((barRef, None, Nil)),
    ),
    (
      ci / stepsStatusReport withInput "-",
      Seq((barRef, None, Nil)),
    ),
    (
      "decrement" forProject foo,
      Seq(
        (fooRef, None, Nil),
        (rootRef, None, Seq(SkippedMessage.ProjectFilter(foo, rootRef))),
      ),
    ),
    (
      "show counter".once,
      Seq(
        (fooRef, None, Nil),
        (rootRef, None, Seq(SkippedMessage.RunOnce)),
      ),
    ),
    (
      Test / test,
      Seq(
        (fooRef, None, Nil),
        (rootRef, None, Nil),
      ),
    ),
    (
      (+publish named "Cross publish"),
      Seq(
        (fooRef, None, Nil),
        (rootRef, None, Nil),
      ),
    ),
    (
      (ci / stepsStatusReport withInput "ci-reports/root.html"),
      Seq((rootRef, None, Nil)),
    ),
    (
      ci / stepsStatusReport withInput "ci-reports/foo.html",
      Seq((fooRef, None, Nil)),
    ),
  )
}

TaskKey[Unit]("afterCiSpec") := {
  val rootRef = thisProjectRef.value
  val barRef = (bar / thisProjectRef).value
  val fooRef = (foo / thisProjectRef).value
  InternalStepsKeys.toTestTuplesByStep(
    (ci / InternalStepsKeys.pendingStepsByStep).value,
    (ci / InternalStepsKeys.stepsResult).value,
  ) shouldBe Seq[InternalStepsKeys.StepsTestTuplesByStep](
    (
      "show scalaVersion",
      Seq(
        (barRef, Some(true), Nil),
      ),
    ),
    (
      "increment".once forProject LocalRootProject,
      Seq(
        (
          barRef,
          Some(true),
          Seq(SkippedMessage.ProjectFilter(LocalRootProject, barRef)),
        ),
        (
          fooRef,
          Some(true),
          Seq(SkippedMessage.ProjectFilter(LocalRootProject, fooRef)),
        ),
        (rootRef, Some(true), Nil),
      ),
    ),
    (
      +(Test / test),
      Seq((barRef, Some(true), Nil)),
    ),
    (
      Docker / publish,
      Seq((
        barRef,
        Some(true),
        Seq(SuccessMessage.Published("bar", "0.1.0-SNAPSHOT")),
      )),
    ),
    (
      ci / stepsStatusReport withInput "-",
      Seq((barRef, Some(true), Nil)),
    ),
    (
      "decrement" forProject foo,
      Seq(
        (fooRef, Some(true), Nil),
        (rootRef, Some(true), Seq(SkippedMessage.ProjectFilter(foo, rootRef))),
      ),
    ),
    (
      "show counter".once,
      Seq(
        (fooRef, Some(true), Nil),
        (rootRef, Some(true), Seq(SkippedMessage.RunOnce)),
      ),
    ),
    (
      Test / test,
      Seq(
        (fooRef, Some(true), Nil),
        (rootRef, Some(true), Nil),
      ),
    ),
    (
      (+publish named "Cross publish"),
      Seq(
        (
          fooRef,
          Some(true),
          Seq(SuccessMessage.Published("foo", "0.1.0-SNAPSHOT")),
        ),
        (rootRef, Some(true), Nil),
      ),
    ),
    (
      (ci / stepsStatusReport withInput "ci-reports/root.html"),
      Seq((rootRef, Some(true), Nil)),
    ),
    (
      ci / stepsStatusReport withInput "ci-reports/foo.html",
      Seq((fooRef, Some(true), Nil)),
    ),
  )
}

val subZero = Def.mapScope(Scope.replaceThis(Global))
TaskKey[Unit]("conflictingCrossVersionsSpec", "") := {
  val barRef = (bar / thisProjectRef).value
  val allResults = InternalStepsKeys.toTestTuplesByStep(
    (ci / InternalStepsKeys.pendingStepsByStep).value,
    (ci / InternalStepsKeys.stepsResult).value,
  )

  // narrow down step results for brevity
  val msgs = for {
    (_, stepResults) <- allResults
    (_, _, msgs) <- stepResults
    msg <- msgs.collectFirst {
      case msg: FailureMessage.Task => msg
    }
  } yield msg
  msgs shouldBe Seq(
    FailureMessage.Task(
      subZero(barRef / update),
      "Conflicting cross-version suffixes in: foo:foo",
    ),
  )
}

TaskKey[Unit]("crossVersionsFallbackSpec", "") := {
  // foo tests
  // main classes for 2.13 should exist
  assertExists((foo / target).value / s"scala-2.13" / "classes" / "Foo.class")
  // test classes for 2.13 should exist
  assertExists(
    (foo / target).value / s"scala-2.13" / "test-classes" / "FooSpec.class",
  )
  assertExists(
    (foo / target).value / s"scala-2.12" / "classes" / "Foo.class",
  )
  // test classes should not exist
  assertExists(
    (foo / target).value / s"scala-2.12" / "test-classes" / "FooSpec.class",
  )

  // bar tests
  // main classes
  assertExists((bar / target).value / s"scala-2.13" / "classes" / "Bar.class")
  // main classes for 2.12 should not exist, because crossScalaVersions is Nil
  assertExists((bar / target).value / s"scala-2.12" / "classes" / "Bar.class")
  // test classes should not exist, because bar does not depend on test classes
  assertNotExists(
    (bar / target).value / s"scala-2.13" / "test-classes" / "BarSpec.class",
  )
  assertNotExists(
    (bar / target).value / s"scala-2.12" / "test-classes" / "BarSpec.class",
  )
}

TaskKey[Unit](
  "commandSpec",
  "\"increment\" and \"decrement\" commands must be run for the right projects",
) := {
  // increment command is configured to run only for root
  (root / counter).value shouldBe 1

  // both commands should not be run for bar
  (bar / counter).value shouldBe 0

  // decrement command should only be run on root
  (foo / counter).value shouldBe -1

  // project must be reset after running the command
  thisProjectRef.value shouldBe (root / thisProjectRef).value
  thisProject.value shouldBe (root / thisProject).value
}

TaskKey[Unit]("rootSpec", "Steps for root project should run correctly") := {
  // main classes for 2.13 should not exist, because of custom scalaVersion
  assertNotExists(
    (root / target).value / s"scala-2.13" / "classes" / "Root.class",
  )
  // test classes for 2.13 should not exist, because of custom scalaVersion
  assertNotExists(
    (root / target).value / s"scala-2.13" / "test-classes" / "RootSpec.class",
  )
  // main classes for 2.12 should exist, because of custom scalaVersion
  assertExists((root / target).value / s"scala-2.12" / "classes" / "Root.class")
  // test classes for 2.12 should exist, because of custom scalaVersion
  assertExists(
    (root / target).value / s"scala-2.12" / "test-classes" / "RootSpec.class",
  )
  // jars should not be published, because publishArtifact := false
  assertNotExists(
    baseDirectory
      .value / "ivy-repo" / "releases" / s"${name.value}" / s"${name.value}_2.12" / version.value,
  )
  assertNotExists(
    baseDirectory
      .value / "ivy-repo" / "releases" / s"${name.value}" / s"${name.value}_2.13" / version.value,
  )
}

TaskKey[Unit]("fooSpec", "Steps for foo project should run correctly") := {
  // main classes for 2.13 should exist
  assertExists((foo / target).value / s"scala-2.13" / "classes" / "Foo.class")
  // test classes for 2.13 should exist
  assertExists(
    (foo / target).value / s"scala-2.13" / "test-classes" / "FooSpec.class",
  )
  // main classes for 2.12 should exist, because of cross publish
  assertExists((foo / target).value / s"scala-2.12" / "classes" / "Foo.class")
  // test classes for 2.12 should not exist
  assertNotExists(
    (foo / target).value / s"scala-2.12" / "test-classes" / "FooSpec.class",
  )
  val base = baseDirectory.value
  // published jar for 2.13 should exist
  assertExists(
    base / "ivy-repo" / "releases" / s"${(foo / name).value}" / s"${(foo / name).value}_2.13" / version.value,
  )
  // published jar for 2.12 should exist
  assertExists(
    base / "ivy-repo" / "releases" / s"${(foo / name).value}" / s"${(foo / name).value}_2.12" / version.value,
  )
}

TaskKey[Unit]("barSpec", "Steps for bar project should run correctly") := {
  // main classes for 2.13 should exist
  assertExists((bar / target).value / s"scala-2.13" / "classes" / "Bar.class")
  // main classes for 2.12 should exist
  assertExists(
    (bar / target).value / s"scala-2.12" / "classes" / "Bar.class",
  )
  // test classes for 2.13 should exist
  assertExists(
    (bar / target).value / s"scala-2.13" / "test-classes" / "BarSpec.class",
  )
  // test classes for 2.12 should exist
  assertExists(
    (bar / target).value / s"scala-2.12" / "test-classes" / "BarSpec.class",
  )
  val base = baseDirectory.value
  // published jar for 2.13 should exist
  assertExists(
    base / "ivy-repo" / "releases" / s"${(bar / name).value}" / s"${(bar / name).value}_2.13" / version.value,
  )
  // published jar for 2.12 should not exist, because no cross publish
  assertNotExists(
    base / "ivy-repo" / "releases" / s"${(bar / name).value}" / s"${(bar / name).value}_2.12" / version.value,
  )
}

TaskKey[Unit](
  "resetScalaVersionSpec",
  "After cross build step scalaVersion should be reset to original",
) := {
  (root / scalaVersion).value shouldBe "2.12.18"
  (foo / scalaVersion).value shouldBe "2.13.16"
  (bar / scalaVersion).value shouldBe "2.13.16"
}

TaskKey[Unit](
  "aggregateCommandSpec",
  "Aggregate setting should be honored in commands",
) := {
  assertExists(target.value / s"scala-2.12" / "classes" / "Root.class")
  assertExists((foo / target).value / s"scala-2.13" / "classes" / "Foo.class")
  assertExists((bar / target).value / s"scala-2.13" / "classes" / "Bar.class")
}
