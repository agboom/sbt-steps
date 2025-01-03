import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbtsteps.internal.cli.*
import sbtrelease.*
import sbtversionpolicy.*
import xerial.sbt.Sonatype
import sys.process.Process
import scala.sys.process.ProcessLogger
import scala.annotation.tailrec
import java.nio.charset.StandardCharsets

/** Release and version policy process adapted from
  * [sbt-version-policy](https://github.com/scalacenter/sbt-version-policy/blob/main/sbt-version-policy/src/sbt-test/sbt-version-policy/example-sbt-release/build.sbt)
  * example. Instead of keeping a version in version.sbt, it is automatically
  * derived from the version policy intention.
  */
object LocalPlugin extends AutoPlugin {
  object autoImport {
    lazy val resetCompatibilityIntention =
      taskKey[Unit](
        "Set versionPolicyIntention to Compatibility.BinaryAndSourceCompatible, and commit the change",
      )

    lazy val draftGitHubRelease =
      taskKey[Unit](
        s"Draft release on GitHub using the commit messages. Must have ${previousReleaseVersion.key.label} defined",
      )

    lazy val releaseNotesLines = inputKey[List[String]](
      "Show and return a template for release notes based on Git log",
    )

    lazy val previousReleaseVersion = settingKey[String](
      "The previous release version",
    )
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
    // custom release process: run `versionCheck` after we have set the release version, and
    // reset compatibility intention to `Compatibility.BinaryAndSourceCompatible` after the release.
    releaseProcess := Seq[ReleaseStep](
      releaseStepCommand("release-vcs-checks"),
      checkSnapshotDependencies,
      inquireAndSetReleaseVersion,
      releaseStepCommand("versionCheck"),
      runClean,
      runTest,
      releaseStepInputTask(scripted),
      tagRelease,
      releaseStepCommand("publishSigned"),
      releaseStepTask(resetCompatibilityIntention),
      pushChanges,
      releaseStepTask(draftGitHubRelease),
    ),
    resetCompatibilityIntention / aggregate := false,
    resetCompatibilityIntention := {
      val log = streams.value.log
      val currentIntention = (ThisBuild / versionPolicyIntention).value
      if (currentIntention == Compatibility.BinaryAndSourceCompatible) {
        log.info(
          "Not changing compatibility intention because it is already set to BinaryAndSourceCompatible",
        )
      } else {
        log.info(
          "Resetting compatibility intention to BinaryAndSourceCompatible...",
        )
        IO.write(
          file("compatibility.sbt"),
          compatibilityLines,
        )
        if (Process("git" :: "add" :: "compatibility.sbt" :: Nil) ! log != 0)
          throw new MessageOnlyException(
            "Failed to stage compatibility.sbt in Git",
          )
        if (
          Process(
            "git" :: "commit" :: "-m" :: "Reset compatibility intention" :: Nil,
          ) ! log != 0
        ) {
          throw new MessageOnlyException("Failed to commit compatibility.sbt")
        }
      }
    },
    draftGitHubRelease / aggregate := false,
    draftGitHubRelease := draftGitHubReleaseTask.value,
    releaseNotesLines / aggregate := false,
    releaseNotesLines := releaseNotesLinesTask.evaluated,
  )

  private val draftGitHubReleaseTask = Def.taskDyn {
    val log = streams.value.log
    val repoBaseUrl = "https://github.com/agboom/sbt-steps"
    val releaseBaseUrl = s"$repoBaseUrl/releases/new"
    val releaseVersion = version.value
    val previousVersion = previousReleaseVersion.?.value.getOrElse(
      throw new MessageOnlyException(
        s"Expected ${previousReleaseVersion.key.label} to be set, did you run sbt release?",
      ),
    )
    val previousTag = s"v$previousVersion"
    val releaseTag = s"v$releaseVersion"

    log.info(
      s"""Drafting new GitHub release for tag "$releaseTag" with title "$releaseVersion".""",
    )
    log.info(
      s"If any error occurs, please open $releaseBaseUrl and fill in the form manually.",
    )
    val git = releaseVcs.value
      .getOrElse(throw new MessageOnlyException("Not in a git repository"))

    if (!git.existsTag(releaseTag)) {
      throw new MessageOnlyException(
        s"Tag v$releaseVersion does not exist. Did you run sbt release?",
      )
    }

    def encode(s: String) =
      java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)

    releaseNotesLines.toTask(s" $previousTag $releaseTag").map { notes =>
      val notesAndFooter = notes ++ List(
        "",
        s"**Full Changelog**: https://github.com/agboom/sbt-steps/compare/$previousTag..$releaseTag",
      )
      val params = List(
        s"title=${encode(releaseVersion)}",
        s"tag=${encode(releaseTag)}",
        s"body=${encode(notes.mkString("\n"))}",
      )

      SimpleReader.readLine(
        "Press any key to open your browser to finish the release...",
      )
      val queryString = params.mkString("?", "&", "")
      java.awt.Desktop.getDesktop.browse(uri(
        s"$releaseBaseUrl$queryString",
      ))
    }
  }

  private val releaseNotesLinesTask = Def.inputTask {
    val (fromRevOpt, toRevOpt) = CLIParser(
      PosArg("from-rev", StringBasic.?).withDefault(None),
      PosArg("to-rev", StringBasic.?).withDefault(None),
    ).parsed
    val log = state.value.log
    val fromRev = fromRevOpt.getOrElse {
      log.info("<from-rev> not passed, assuming first commit")
      Process("git" :: "rev-list" :: "HEAD" :: Nil).lineStream(log).lastOption
        .getOrElse {
          throw new MessageOnlyException("No first commit found")
        }
    }
    val toRev = toRevOpt.getOrElse {
      log.info("<to-rev> not passed, assuming HEAD")
      "HEAD"
    }
    // get list of commit hashes in the given range for later reference
    val revRange = s"$fromRev..$toRev"
    val revs = Process(
      "git" :: "rev-list" :: "--no-merges" :: revRange :: Nil,
    )
      .lineStream(log)
      .toList

    // machine-readable git log formatted as follows:
    // subject (%s)
    // body (%b)
    // full hash (%H)
    val logLines = Process(
      "git" :: "log" :: "--pretty=%s%n%b%n%H" :: "--no-merges" :: revRange :: Nil,
    )
      .lineStream(log)
      .toList

    // loops over revision list and gets the relevant log lines
    // subjects that do not adhere to Conventional Commits are skipped
    @tailrec
    def categorize(
      revs: List[String],
      logLines: List[String],
      done: List[(String, String)],
    ): List[(String, String)] = revs match {
      case Nil =>
        done
      case rev :: tailRevs =>
        val idx = logLines.indexWhere(_ == rev)
        if (idx == -1) {
          throw new IllegalStateException(
            s"No line found matching rev $rev. This is probably a bug.",
          )
        }
        val commitLog = logLines.take(idx)
        // drop the revision line
        val tailLog = logLines.drop(idx + 1)
        commitLog match {
          case Nil =>
            done
          case commitSubject :: commitBody =>
            val changeOpt = categorizeCommit(commitSubject, commitBody)
            if (changeOpt.isEmpty) {
              log.info(
                "Skipping commit because it doesn't match the Conventional Commits format:",
              )
              log.info(s"${rev.take(7)}: $commitSubject")
            }
            categorize(tailRevs, tailLog, done ++ changeOpt)
        }
    }

    val lines = categorize(revs, logLines, Nil)
      // give the commit types a nice Markdown flavored header
      .groupBy {
        case ("breaking", _) =>
          "## ⚠ Breaking changes"
        case ("feat", _) =>
          "## 🚀 Features"
        case ("fix", _) =>
          "## 🐞 Bug fixes"
        case _ =>
          "## 🎬 Behind the scenes"
      }.flatMap {
        case (header, changes) =>
          header +: changes.distinct.map {
            case (_, line) => s"- $line"
          } :+ ""
      }.toList
    log.info("\n")
    log.info(s"Release notes for $revRange:")
    log.info(lines.mkString("\n"))
    lines
  }

  private def categorizeCommit(
    commitSubject: String,
    commitBody: List[String],
  ): Option[(String, String)] = {
    val subjectRegex =
      "^(.{1,10})(\\(.{1,20}\\))?(\\!)?: (.{1,100})( \\(#[0-9]+\\))?".r
    commitSubject match {
      case subjectRegex(
            commitType,
            scope,
            breaking,
            description,
            pullNr,
          ) =>
        if (
          // https://www.conventionalcommits.org/en/v1.0.0/#specification part 1 and 13
          Option(breaking).isDefined || commitBody.exists(
            _.contains("BREAKING CHANGE"),
          )
        ) {
          Some("breaking" -> commitSubject)
        } else {
          Some(commitType -> commitSubject)
        }
      case _ =>
        None
    }
  }

  private lazy val compatibilityLines =
    """/* See [[https://github.com/scalacenter/sbt-version-policy#1-set-versionpolicyintention]]
      | * on when and how to change this setting.
      | *
      | * Every CI build, the binary compatibility is checked against the intention.
      | * During release, the released version is checked against the intention.
      | */
      |ThisBuild / versionPolicyIntention := Compatibility.BinaryAndSourceCompatible""".stripMargin

  private lazy val devnull = ProcessLogger(_ => (), _ => ())

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
    throw new MessageOnlyException(
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

    // store the previous version in state for future reference
    val prevV = Version(currentV)
      .getOrElse(formatError(currentV))
      .withoutQualifier
      .unapply

    val (_, releaseFunc) = extracted.runTask(releaseVersion, st)
    val suggestedReleaseV = releaseFunc(currentV)

    st.log.info("Press enter to use the default value")

    val releaseV = readVersion(
      suggestedReleaseV,
      "Release version [%s] : ",
      useDefs,
      st.get(ReleaseKeys.commandLineReleaseVersion).flatten,
    )
    reapply(
      Seq(
        ThisBuild / version := releaseV,
        ThisBuild / previousReleaseVersion := prevV,
      ),
      st,
    )
  }
}
