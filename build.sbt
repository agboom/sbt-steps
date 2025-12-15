import com.typesafe.tools.mima.core.*

inThisBuild(Seq(
  organization := "io.github.agboom",
  homepage := Some(url(s"https://github.com/${gitHubOrganization.value}/${gitHubRepository.value}")),
  licenses := List(
    "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"),
  ),
  scmInfo := {
    val org = gitHubOrganization.value
    val repo = gitHubRepository.value
    Some(ScmInfo(url(s"https://github.com/$org/$repo"), s"scm:git@github.com:$org/$repo.git"))
  },
  developers := List(
    Developer(
      "agboom",
      "Adriaan Groenenboom",
      "agboom@pm.me",
      url("https://agboom.github.io"),
    ),
  ),
  console / initialCommands := """import sbtsteps._""",
  // set up sbt-scripted for testing sbt plugins
  scriptedLaunchOpts ++= Seq(
    "-Xmx1024M",
    s"-Dplugin.version=${(ThisProject / version).value}",
    "-Dsbt.color=true",
  ),
  scriptedBufferLog := false,
  versionScheme := Some(VersionScheme.SemVerSpec),
  scalacOptions := Settings.compilerOptions,
  versionPolicyIgnoredInternalDependencyVersions := Some("^\\d+\\.\\d+\\.\\d+\\+\\d+".r),
  ci / steps := Seq(
    versionPolicyCheck,
    Test / test,
    scripted,
  ),
))

lazy val sbtSteps = project.in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-steps",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    scriptedDependencies := Seq(
      sbtTestkit / publishLocal,
      publishLocal,
    ).dependOn.value,
    // internal package does not have compatibility guarantees, so we exclude it
    mimaBinaryIssueFilters += ProblemFilters.exclude[Problem]("sbtsteps.internal.*"),
    mimaPreviousArtifacts := {
      val currentVersion = version.value
      val previousVersion = sbtrelease.Version(currentVersion)
        .getOrElse(LocalPlugin.formatError(currentVersion))
        .withoutQualifier
        .unapply
      Set(
        Defaults.sbtPluginExtra(
          organization.value %% name.value % previousVersion,
          sbtBinaryVersion.value,
          scalaBinaryVersion.value
        ),
      )
    },
    // https://github.com/sbt/sbt-pgp/issues/170
    pgpSigningKey := Credentials
      .forHost(credentials.value, organization.value)
      .map(_.userName),
    // https://github.com/xerial/sbt-sonatype#sonatype-central-host
    publishTo := sonatypePublishToBundle.value,
    sbtPluginPublishLegacyMavenStyle := false,
    sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost,
  )

lazy val sbtTestkit = project.in(file("testkit"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-testkit",
    // only used internally for scripted plugins
    publish / skip := true,
    publishLocal / skip := false,
    ci / steps := Nil,
  )
