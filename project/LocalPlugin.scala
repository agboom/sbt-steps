import sbt.*
import sbt.Keys.*
import sbtrelease.*
import sbtversionpolicy.*
import xerial.sbt.Sonatype

/** Release and version policy process adapted from
  * [sbt-version-policy](https://github.com/scalacenter/sbt-version-policy/blob/main/sbt-version-policy/src/sbt-test/sbt-version-policy/example-sbt-release/build.sbt)
  * example. Instead of keeping a version in version.sbt, it is automatically
  * derived from the version policy intention. As a bonus, the CHANGELOG.md file
  * is automatically updated with the release version.
  */
object LocalPlugin extends AutoPlugin {
  object autoImport {
    lazy val resetCompatibilityIntention =
      taskKey[Unit](
        "Set versionPolicyIntention to Compatibility.BinaryAndSourceCompatible, and commit the change",
      )

    lazy val setVersionInChangelog =
      taskKey[Unit]("Set value of version in CHANGELOG.md")

  }

  import autoImport.*
  import ReleasePlugin.autoImport.*
  import SbtVersionPolicyPlugin.autoImport.*
  import ReleaseTransformations.*
  import Sonatype.autoImport.*
  import ScriptedPlugin.autoImport.*

  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins =
    ReleasePlugin && SbtVersionPolicyPlugin && Sonatype

  override def globalSettings = ReleasePlugin.extraReleaseCommands

  override def projectSettings = Def.settings(
    // needed for sbt-release to accept the version format
    version := version.value.replace('+', '-'),
    // don't use ThisBuild for version
    releaseUseGlobalVersion := false,
    // configure releaseVersion to bump the patch, minor, or major version number according
    // to the versionPolicyIntention setting in compatibility.sbt.
    releaseVersion := fromCompatibility(versionPolicyIntention.value),
    // normally version.sbt, but now we use it to auto commit the release version in the changelog
    releaseVersionFile := baseDirectory.value / "CHANGELOG.md",
    releaseCommitMessage := s"Releasing v${version.value}",
    // custom release process: run `versionCheck` after we have set the release version, and
    // reset compatibility intention to `Compatibility.BinaryAndSourceCompatible` after the release.
    releaseProcess := Seq[ReleaseStep](
      releaseStepCommand("release-vcs-checks"),
      checkSnapshotDependencies,
      checkChangelog,
      inquireAndSetReleaseVersion,
      releaseStepCommand("versionCheck"),
      runClean,
      runTest,
      releaseStepInputTask(scripted),
      releaseStepTask(setVersionInChangelog),
      commitReleaseVersion,
      tagRelease,
      releaseStepCommand("publishSigned"),
      releaseStepTask(resetCompatibilityIntention),
      pushChanges,
    ),
    resetCompatibilityIntention / aggregate := false,
    resetCompatibilityIntention := {
      val log = streams.value.log
      val intention = (ThisBuild / versionPolicyIntention).value
      if (intention == Compatibility.BinaryAndSourceCompatible) {
        log.info(
          "Not changing compatibility intention because it is already set to BinaryAndSourceCompatible",
        )
      } else {
        log.info("Reset compatibility intention to BinaryAndSourceCompatible")
        IO.write(
          file("compatibility.sbt"),
          "ThisBuild / versionPolicyIntention := Compatibility.BinaryAndSourceCompatible\n",
        )
        val gitAddExitValue =
          sys.process.Process("git" :: "add" :: "compatibility.sbt" :: Nil).run(
            log,
          ).exitValue()
        assert(
          gitAddExitValue == 0,
          s"Command failed with exit status $gitAddExitValue",
        )
        val gitCommitExitValue =
          sys.process.Process(
            "git" :: "commit" :: "-m" :: "Reset compatibility intention" :: Nil,
          )
            .run(log)
            .exitValue()
        assert(
          gitCommitExitValue == 0,
          s"Command failed with exit status $gitCommitExitValue",
        )
      }
    },
    setVersionInChangelog / aggregate := false,
    setVersionInChangelog := {
      val changelogFile = releaseVersionFile.value
      val releaseVersion = version.value
      val lines = IO.readLines(changelogFile)

      lazy val newChangelogLines = List(
        "## [Unreleased]",
        "### Added|Changed|Removed|Fixed",
        "- ",
        "",
        s"## [$releaseVersion]",
      )

      val idx = lines.indexWhere(_.toLowerCase.contains(unreleasedSubstr))

      streams.value.log.info(
        s"Updating ${changelogFile.name} with version [$releaseVersion]...",
      )

      lines.lift(idx) match {
        case Some(_) =>
          val newLines =
            lines.take(idx) ++ newChangelogLines ++ lines.drop(idx + 1)
          IO.writeLines(changelogFile, newLines, append = false)
        case _ =>
          sys.error(
            s"""No line matching "$unreleasedSubstr" in ${changelogFile.name}""",
          )
      }
    },
  )

  /** @return
    *   a [release version
    *   function](https://github.com/sbt/sbt-release?tab=readme-ov-file#custom-versioning)
    *   that bumps the patch, minor, or major version number depending on the
    *   provided compatibility level.
    * @param qualifier
    *   Optional qualifier to append to the version (e.g. `"-RC1"`).
    * @note
    *   Adapted from
    *   [sbt-version-policy](https://github.com/scalacenter/sbt-version-policy/blob/354e93a20e17214aed5db149e374344580246409/sbt-version-policy/src/main/scala/sbtversionpolicy/withsbtrelease/ReleaseVersion.scala#L21)
    *   except that it doesn't depend on a '''version.sbt'''.
    */
  def fromCompatibility(
    compatibility: Compatibility,
    qualifier: String = "",
  ): String => String = {
    val bump =
      compatibility match {
        case Compatibility.None => Version.Bump.Major
        case Compatibility.BinaryCompatible => Version.Bump.Minor
        case Compatibility.BinaryAndSourceCompatible => Version.Bump.Bugfix
      }
    { (currentVersion: String) =>
      val nextVersion =
        Version(currentVersion)
          .getOrElse(formatError(currentVersion))
          .withoutQualifier
          .bump(bump)
      nextVersion.unapply + qualifier
    }
  }

  def formatError(version: String) =
    sys.error(
      s"Version [$version] format is not compatible with ${Version.VersionR.pattern}",
    )

  /** Step to inquire only the release version, not the next version, since it's
    * not needed. Also sets the release version directly, no separate step.
    *
    * Adapted from
    * [inquireVersions](https://github.com/sbt/sbt-release/blob/52456d18d20afeb0e9fefb6ed0dae5adcca810ec/src/main/scala/ReleaseExtra.scala#L34).
    */
  lazy val inquireAndSetReleaseVersion: ReleaseStep = { st: State =>
    val extracted = Project.extract(st)

    val useDefs = st.get(ReleaseKeys.useDefaults).getOrElse(false)
    val currentV = extracted.get(version)

    val (_, releaseFunc) = extracted.runTask(releaseVersion, st)
    val suggestedReleaseV = releaseFunc(currentV)

    st.log.info("Press enter to use the default value")

    val releaseV = readVersion(
      suggestedReleaseV,
      "Release version [%s] : ",
      useDefs,
      st.get(ReleaseKeys.commandLineReleaseVersion).flatten,
    )
    reapply(Seq(version := releaseV), st)
  }

  lazy val unreleasedSubstr = "## [unreleased]"

  /** Check changelog early so that we don't have to re-run all tests
    * unnecessarily.
    */
  lazy val checkChangelog: ReleaseStep = { st: State =>
    import scala.collection.JavaConverters.*
    val extracted = Project.extract(st)
    val changelogFile = extracted.get(releaseVersionFile)
    val lines = IO.readLines(changelogFile)
    val goodChangelog = IO.reader(changelogFile) { buffer =>
      buffer.lines.iterator.asScala.exists(
        _.toLowerCase.contains(unreleasedSubstr),
      )
    }
    if (goodChangelog) {
      st.log.info(s"${changelogFile.name} OK")
    } else {
      sys.error(
        s"""No line matching "$unreleasedSubstr" in ${changelogFile.name}""",
      )
    }
    st
  }
}