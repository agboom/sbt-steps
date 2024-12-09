# Plugin development

This document outlines ways to extend `StepsPlugin` by creating your own `AutoPlugin`. We
assume some familiarity with plugin development. Read the [sbt documentation] for more
information.

[sbt documentation]: https://www.scala-sbt.org/1.x/docs/Plugins.html#Creating+an+auto+plugin

## Share steps across builds with a plugin

While sharing steps in the same build definition is done with a shared setting, having
consistent steps across builds requires a shared plugin. This can be useful if you have a
set of repositories that require the same steps.

Below is an example of a shared steps plugin that uses `CIStepsPlugin` and two community
plugins:

```scala
import sbt.*
import sbt.Keys.*
import sbtunidoc.ScalaUnidocPlugin
import sbtversionpolicy.SbtVersionPolicyPlugin
import sbtsteps.CIStepsPlugin

object SharedPlugin extends AutoPlugin {
  import CIStepsPlugin.autoImport.*

  override lazy val requires: Plugins = CIStepsPlugin && ScalaUnidocPlugin && SbtVersionPolicyPlugin

  override lazy val projectSettings = Def.settings(
    ci / steps := Seq(
      versionPolicyCheck,
      +(Test / test),
      +publish,
      (Compile / unidoc),
    ),
  )
}
```

> [!NOTE]
> Both [`ScalaUnidocPlugin`] and [`SbtVersionPolicyPlugin`] may require more configuration to
> work.

[`ScalaUnidocPlugin`]: https://github.com/sbt/sbt-unidoc/
[`SbtVersionPolicyPlugin`]: https://github.com/scalacenter/sbt-version-policy

## Create your own steps plugin

You can create your own `StepsPlugin` by defining a custom steps task. This task will be
the scope of the steps configuration. Scoping allows you to define multiple `steps`
configurations for different use cases simultaneously. One example is to have a separate
set of steps for a release sequence. Another example could be steps for Continuous
Deployment.

Here's an example of a possible Continuous Deployment plugin:

```scala
import sbt.*
import sbt.Keys.*
import sbtsteps.StepsPlugin

object CDPlugin extends AutoPlugin {
  object autoImport {
    lazy val cd = inputKey[StateTransform]("Run Continuous Deployment for all projects")

    lazy val cdDeploy = inputKey[Unit]("Deploy this project")

    // by using surround in the status message, the `name` and `version` constituents are
    // accentuated bold in ASCII and with a code block in HTML.
    case class DeploySuccessMessage(serviceName: String, version: String) 
        extends CustomSuccessMessage({
          params => 
            s"Successfully deployed ${params.surround(serviceName)} ${params.surround(version)}."
        })
    
    case class DeployFailureMessage(serviceName: String, version: String)
        extends CustomFailureMessage.Task(cdDeploy) {
          params =>
            s"Failed to deploy ${params.surround(serviceName)} ${params.surround(version)}."
        }
  }

  import autoImport.*

  override lazy val requires: Plugins = StepsPlugin

  // configure the cd task as a steps task and set defaults in the cd scope
  override lazy val globalSettings = StepsPlugin.stepsSettings(cd) ++
    // set and override additional global settings here
    Def.settings(
      // give it a meaningful name to distinguish from other CI plugins
      // logged during runtime as "Starting CD with the following steps:"
      cd / name := "CD",
      // show a message below the step status
      // note that messages for a success result do not necessarily have to be a 
      // `SuccessMessage`. For example, if one task performs multiple deploys that can
      // partially fail, the succeeded step can contain both success- and failure messages.
      cd / stepsMessagesForSuccess += TaskMessageBuilder.forSuccessSingle(cdDeploy) {
        case (_, project, extracted) =>
          DeploySuccessMessage(extracted.get(project / name), extracted.get(project / version))
      },
      // this will show a message if a deploy has failed
      // the exception message is shown by default
      cd / ciMessagesForFailure += TaskMessageBuilder.forFailureSingle(cdDeploy) {
        case (_, project, extracted) =>
          DeployFailureMessage(extracted.get(project / name), extracted.get(project / version))
      },
      // show a message why a deploy is skipped (only shown when --verbose is passed)
      cd / ciMessagesForSkipped += TaskMessageBuilder.forSkippedSingle(cdDeploy) {
        case _ =>
          new CustomSkippedMessage({ params =>
            s"""${params.surround("cdDeploy / skip")} is true if one of: 
               |- ${params.surround("cd / skip")} is true
               |- ${params.surround("publish / skip")} is true 
               |- ${params.surround("publishArtifact")} is false""".stripMargin
          })
      },
      cdDeploy / skip := {
        (cd / skip).value || (publish / skip).value || !publishArtifact.value
      },
      // do deploy per project
      cd / stepsGrouping := StepsGrouping.ByProject,
  )


  // we use the project settings to pass the service names and version
  override lazy val projectSettings = Def.settings(
    cdDeploy := {
      doDeploy(name.value, version.value)
    },
    cd / steps := {
      // when a deploy fails, we continue to the next, but the error is shown
      Seq(
        cdDeploy.continueOnError named s"service ${name.value}"
      )
    }
  )
}
```

Below shows how the new `CDPlugin` may be configured:

```sbt
lazy val fooService = (project in file("foo"))
  .enablePlugins(CDPlugin)
  .settings(
    name := "foo-service",
  )

lazy val barService = (project in file("bar"))
  .enablePlugins(CDPlugin)
  .settings(
    name := "bar-service",
  )

lazy val root = (project in file("."))
  .enablePlugins(CDPlugin)
  .settings(
    name := "root",
    // skip the cdDeploy step for this project
    cdDeploy / skip := true,
  )
```

This will result in the following step sequence:

```
sbt:root> cd/stepsTree -s
[info] task: deploy
[info]   +-project steps:
[info]     +-task: fooService / cdDeploy
[info]       +-status: succeeded
[info]         +-Successfully deployed foo-service
[info]     +-task: barService / cdDeploy
[info]       +-status: succeeded
[info]         +-Successfully deployed bar-service
```

Here are some examples how you would use `CDPlugin` in an sbt shell:

```
# run CD steps
> cd

# print the steps tree with status and skipped steps
> cd/stepsTree -sv

# create a status report (written to target/cd-status.html)
# note that this is done automatically on `cd`
> cd/stepsStatusReport
```

> [!IMPORTANT]
> Notice the use of the `cd/` task scope for settings and tasks. A task scope is always
> required and must be the task that the steps plugin is run with, like `ci/` or `cd/`.
