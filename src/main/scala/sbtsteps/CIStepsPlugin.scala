package sbtsteps

import sbt.*
import sbt.Keys.*

object CIStepsPlugin extends AutoPlugin {

  object autoImport {
    final lazy val ci = inputKey[StateTransform](
      """Runs the configured CI steps for the entire build definition.
      |
      |Usage: ci [OPTIONS]...
      |
      |OPTIONS:
      |-v, --verbose   show more information (e.g. skipped steps and fields that deviate from default)
      |""".stripMargin,
    )
  }

  import autoImport.*
  import StepsPlugin.autoImport.*

  final case class Published(artifactName: String, artifactVersion: String)
      extends CustomSuccessMessage({ params =>
        s"Successfully published $artifactName ${params.surround(artifactVersion)}"
      })

  override lazy val requires: Plugins = StepsPlugin

  override lazy val trigger: PluginTrigger = allRequirements

  override lazy val globalSettings: Seq[Def.Setting[?]] =
    StepsPlugin.stepsSettings(ci) ++ Def.settings(
      ci / name := "CI",
      ci / steps := Seq(
        +(Test / test),
        +publish,
      ),
      ci / stepsMessagesForSuccess ++= Seq(
        TaskMessageBuilder.forSuccessSingle(publish) {
          case (_, ref, extracted) if extracted.get(ref / publishArtifact) =>
            SuccessMessage.Published(
              artifactName = extracted.get(ref / name),
              artifactVersion = extracted.get(ref / version),
            )
        },
        TaskMessageBuilder.forSuccessSingle(publishLocal) {
          case (_, ref, extracted) if extracted.get(ref / publishArtifact) =>
            SuccessMessage.Published(
              artifactName = extracted.get(ref / name),
              artifactVersion = extracted.get(ref / version),
            )
        },
      ),
    )
}
