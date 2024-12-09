import sbt.*
import sbtsteps.StepsPlugin

object CustomStepsPlugin extends AutoPlugin {
  import StepsPlugin.autoImport.*

  object autoImport {

    lazy val custom = inputKey[StateTransform]("")

    lazy val customTask = taskKey[String]("")

    case class CustomTaskSuccessMessage(result: String)
        extends CustomSuccessMessage({
          params =>
            s"Successfully completed custom task with result: ${params.surround(result)}"
        })
  }

  import autoImport.*

  override lazy val requires: Plugins = StepsPlugin

  override lazy val globalSettings = StepsPlugin.stepsSettings(custom) ++ Seq(
    customTask := "done",
    custom / stepsMessagesForSuccess += TaskMessageBuilder.forSuccessSingle(
      customTask,
    ) {
      case (result, _, _) =>
        CustomTaskSuccessMessage(result)
    },
  )
}
