import sbt.*
import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbtsteps.internal.cli.*
import sbtrelease.*
import sbtversionpolicy.*
import xerial.sbt.Sonatype

import sttp.client3.quick.*
import sttp.client3.upicklejson.*
import sttp.client3.HttpClientSyncBackend
import upickle.default.*

import java.nio.charset.StandardCharsets

import scala.sys.process.{Process, ProcessLogger}
import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal

/** Release and version policy process adapted from
  * [sbt-version-policy](https://github.com/scalacenter/sbt-version-policy/blob/main/sbt-version-policy/src/sbt-test/sbt-version-policy/example-sbt-release/build.sbt)
  * example. Instead of keeping a version in version.sbt, it is automatically
  * derived from the version policy intention.
  */
object LocalPlugin extends AutoPlugin {
  object autoImport {
    lazy val gitHubOrganization = settingKey[String]("GitHub organization")

    lazy val gitHubRepository = settingKey[String]("GitHub repository")

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

  override def buildSettings = Def.settings(
    gitHubOrganization := "agboom",
    gitHubRepository := "sbt-steps",
  )

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

    releaseNotesLines.toTask(s" --include-authors $previousTag $releaseTag")
      .map { notes =>
        val notesAndFooter = notes ++ List(
          "",
          s"**Full Changelog**: https://github.com/agboom/sbt-steps/compare/$previousTag..$releaseTag",
        )
        val params = List(
          s"title=${urlEncode(releaseVersion)}",
          s"tag=${urlEncode(releaseTag)}",
          s"body=${urlEncode(notes.mkString("\n"))}",
        )

        log.info(
          "Please make sure your browser is signed into GitHub in the next step.",
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

  private def urlEncode(s: String) =
    java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)

  private val releaseNotesLinesTask = Def.inputTask {
    val (includeAuthors, fromRevOpt, toRevOpt) = CLIParser(
      Flag('a', "include-authors"),
      PosArg("from-rev", StringBasic.?).withDefault(None),
      PosArg("to-rev", StringBasic.?).withDefault(None),
    ).parsed
    val log = streams.value.log
    val org = gitHubOrganization.value
    val repo = gitHubRepository.value
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
    // get list of commit hashes and dates in the given range for later reference:
    // full hash (%H)
    // author date (%as)
    val revRange = s"$fromRev..$toRev"
    val revDateRegex = "^(.*) (.*)$".r
    val (revs, dates) = Process(
      "git" :: "rev-list" :: "--format=%H %as"
        :: "--no-commit-header" :: "--no-merges" :: revRange :: Nil,
    )
      .lineStream(log)
      .toList
      .foldRight(List.empty[String] -> List.empty[String]) {
        case (revDateRegex(rev, date), (revs, dates)) =>
          (rev :: revs) -> (date :: dates)
      }

    // machine-readable git log formatted as follows:
    // subject (%s)
    // body (%b)
    // full hash (%H)
    val logLines = Process(
      "git" :: "log" :: "--pretty=%s%n%b%n%H" :: "--no-merges" :: revRange :: Nil,
    )
      .lineStream(log)
      .toList

    val authors: Map[GitRev, Author] = Try {
      for {
        lastDate <- dates.headOption if includeAuthors
        // GH API's until is exclusive, so add one day
        until = java.time.LocalDate.parse(lastDate) plusDays 1
        from <- dates.lastOption
      } yield getGitHubAuthors(org, repo, from -> s"$until")
    }.fold(
      {
        case NonFatal(ex) =>
          log.error(
            "Failed to get GitHub authors. Continuing creating release notes without.",
          )
          log.trace(ex)
          Map.empty
      },
      _.getOrElse(Map.empty),
    )

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
              .map {
                // if the profile name for the commit author is found, add to line
                case (category, change) =>
                  val authorAnnotation = authors.get(rev)
                    .map(author => s" by @$author")
                    .getOrElse("")
                  category -> s"$change$authorAnnotation"
              }
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
    log.info("\n")
    lines
  }

  private type GitRev = String
  private type Author = String

  // gets profile names from commit hashes using GH API
  // public GH API does not allow querying commits by hash so we use a date range
  // with this method we don't need an API token for now
  // https://docs.github.com/en/rest/commits/commits?apiVersion=2022-11-28#list-commits
  private def getGitHubAuthors(
    org: String,
    repo: String,
    sinceUntil: (String, String),
  ): Map[GitRev, Author] = {
    val baseUrl = s"https://api.github.com/repos/$org/$repo/commits"
    val (since, until) = sinceUntil
    lazy val backend = HttpClientSyncBackend()
    val response = quickRequest
      .get(uri"$baseUrl?since=$since&until=$until")
      .contentType("application/vnd.github+json")
      // .auth.bearer(sys.env("GITHUB_TOKEN"))
      .header("X-GitHub-Api-Version", "2022-11-28")
      .response(asJsonAlways[ujson.Value])
      .send(backend)

    if (!response.code.isSuccess) {
      throw new MessageOnlyException(
        s"Failed to get authors from GitHub API, response code ${response.code}",
      )
    } else {
      response.body.fold(
        throw _, {
          _.arr.flatMap { value =>
            val obj = value.obj
            for {
              sha <- obj.get("sha")
              author <- obj.get("author")
              login <- author.obj.get("login")
            } yield sha.str -> login.str
          }
        },
      )
    }.toMap
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
