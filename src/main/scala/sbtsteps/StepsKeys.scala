package sbtsteps

import sbt.{internal as _, *}

private[sbtsteps] trait StepsKeys {

  final lazy val steps = settingKey[Seq[Step]]("Steps to run")
    // steps are resolved dynamically, so prevent unused warning
    .withRank(KeyRanks.Invisible)

  final lazy val stepsTree = inputKey[Unit](
    """Prints the aggregated steps for the build definition in a structured tree.
      |
      |Usage: stepsTree [OPTIONS]...
      |
      |OPTIONS:
      |-v, --verbose   show more information (e.g. skipped steps and fields that deviate from default)
      |-s, --status    show the status of the latest run
      |""".stripMargin,
  )

  final lazy val stepsGrouping = settingKey[StepsGrouping](
    """Sets how the aggregated steps are grouped:
      |- StepsGrouping.ByStep will group project steps with the same task or command. The steps are topologically sorted. This is the default.
      |- StepsGrouping.ByProject will group project steps by project in the order they're configured.""".stripMargin,
  )

  final lazy val stepsStatusReport = inputKey[String](
    s"""Generate a status report based on the aggregated steps and the completed status. Either logs the result or writes it to a file.
      |
      |Usage: stepsStatusReport [OPTIONS]... [PATH]
      |
      |OPTIONS:
      |-v, --verbose   show more information (e.g. skipped steps and fields that deviate from default)
      |PATH:
      |Either a path to a directory or file, or '-' to log the result.
      |If it's a path to a file, the file is is used.
      |If it's a directory, "$$PATH/$${ci / stepsStatusReportFileName.value}" is used.
      |If the file already exists, it is overwritten.
      |If PATH is omitted "target/$${ci / stepsStatusReportFileName.value}" is used.
      |""".stripMargin,
  )

  final lazy val stepsStatusReportFileName =
    settingKey[String]("File name for the status report if none is given.")

  final lazy val stepsMessagesForSuccess =
    settingKey[Seq[MessageBuilder[StepResult.Succeeded, ResultMessage]]](
      """Add messages to a succeeded step, shown with the step status in the  report.
      |Note: always set in the Global scope, not in ThisBuild or project scope.
      |""".stripMargin,
    )
  final lazy val stepsMessagesForFailure = settingKey[Seq[MessageBuilder[
    StepResult.Failed,
    ResultMessage,
  ]]](
    """Add messages to a failed step, shown with the step status in the  report.
      |Note: always set in the Global scope, not in ThisBuild or project scope.
      |""".stripMargin,
  )
  final lazy val stepsMessagesForSkipped =
    settingKey[Seq[MessageBuilder[StepResult.Skipped, ResultMessage]]](
      """Add messages to a skipped step, shown with the step status in the  report.
      |Note: always set in the Global scope, not in ThisBuild or project scope.
      |""".stripMargin,
    )

  implicit final def taskToStep(task: TaskKey[?]): TaskStep = TaskStep(task)

  implicit final def inputTaskToStep(task: InputKey[?]): InputTaskStep =
    InputTaskStep(task)

  implicit final def commandToStep(cmd: String): CommandStep = CommandStep(cmd)

  final type Step = model.Step

  final type TaskStep = model.TaskStep
  final val TaskStep = model.TaskStep

  final type InputTaskStep = model.InputTaskStep
  final val InputTaskStep = model.InputTaskStep

  final type CommandStep = model.CommandStep
  final val CommandStep = model.CommandStep

  final type StepsGrouping = model.StepsGrouping
  final val StepsGrouping = model.StepsGrouping

  final type StepResult = model.StepResult
  final val StepResult = model.StepResult

  final type ResultMessage = model.ResultMessage

  final type SuccessMessage = model.SuccessMessage
  final val SuccessMessage = model.SuccessMessage

  final type SkippedMessage = model.SkippedMessage
  final val SkippedMessage = model.SkippedMessage

  final type FailureMessage = model.FailureMessage
  final val FailureMessage = model.FailureMessage

  final type CustomSuccessMessage = model.CustomSuccessMessage
  final val CustomSuccessMessage = model.CustomSuccessMessage
  final val CustomFailureMessage = model.CustomFailureMessage
  final type CustomSkippedMessage = model.CustomSkippedMessage

  final type Surround = model.Surround
  final val Surround = model.Surround

  final type ShowMessageParams = model.MessageParams
  final val ShowMessageParams = model.MessageParams

  final type MessageBuilder[R <: StepResult, M <: ResultMessage] =
    model.MessageBuilder[R, M]
  final val MessageBuilder = model.MessageBuilder

  final val TaskMessageBuilder = model.TaskMessageBuilder
  final val CommandMessageBuilder = model.CommandMessageBuilder
}
