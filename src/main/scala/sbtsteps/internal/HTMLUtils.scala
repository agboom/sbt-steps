package sbtsteps
package internal

import sbt.*

import Table.*

import model.*

object HTMLUtils {
  private lazy val failureMoji = ":red_square:"
  private lazy val failureSubMoji = ":x:"
  private lazy val successMoji = ":white_check_mark:"
  private lazy val successSubMoji = ":green_circle:"
  private lazy val notRunMoji = ":white_large_square:"
  private lazy val notRunSubMoji = ":white_circle:"

  private lazy val code = "<code>" -> "</code>"

  // create special Show instances that surround values with code tags
  private lazy val showExecFactory = StepsUtils.ShowExecFactory(surround = code)
  private lazy val showKeyFactory = StepsUtils.ShowKeyFactory(surround = code)

  def tableRowsToHtml(tableRows: Seq[TableRow]): String =
    if (tableRows.nonEmpty) {
      table(tableRows*).toHtml
    } else {
      table(tr("<b>No steps in this build</b>" width 8000)).toHtml
    }

  def pendingStepsByProjectToHtml(
    steps: Seq[(ProjectRef, Seq[PendingStep])],
    verbose: Boolean,
    stepsResult: InternalStepsKeys.StepsResult,
    extracted: Extracted,
  ): String = {
    // used to show project-specific steps
    lazy val showPendingStep: Show[PendingStep] =
      StepsUtils.createShowPendingStep(
        extracted,
        surround = code,
        includeStepName = true,
        includeRootProjectName = false,
      )
    val tableRows = steps.flatMap {
      case (ProjectRef(_, projectName), steps) =>
        val stepRows = pendingStepsToTableRows(
          steps,
          verbose,
          stepsResult,
          showPendingStep,
          extracted,
        )
        if (stepRows.nonEmpty) {
          // width is set to a large number to fill the entire width of the page
          tr(s"<b>$projectName</b>" colspan 5 width 8000) +: stepRows
        } else {
          tr(s"<b>$projectName</b> (no steps)" colspan 5 width 8000) :: Nil
        }
    }

    tableRowsToHtml(tableRows)
  }

  def pendingStepsByStepToHtml(
    steps: Seq[(Step, Seq[PendingStep])],
    verbose: Boolean,
    stepsResult: InternalStepsKeys.StepsResult,
    extracted: Extracted,
  ): String = {
    lazy val showExec = showExecFactory.create(extracted)
    lazy val showKey = showKeyFactory.create(extracted)
    // used in the step grouping header
    lazy val showStep: Show[Step] = StepsUtils.createShowStep(
      extracted,
      showKeyFactory.copy(
        // omit the project name from the header, this is shown in the project-specific step
        scopeMask = ScopeMask(project = false),
      ),
      showExecFactory,
      surround = "<b>" -> "</b>",
      includeStepName = true,
    )
    // used in the project-specific steps
    lazy val showPendingStep: Show[PendingStep] =
      StepsUtils.createShowPendingStep(
        extracted,
        surround = code,
        // step name already included in the header
        includeStepName = false,
        // show the project name
        includeRootProjectName = true,
      )
    val tableRows = steps.flatMap {
      case (ciStep, steps) =>
        val stepRows = pendingStepsToTableRows(
          steps,
          verbose,
          stepsResult,
          showPendingStep,
          extracted,
        )
        if (stepRows.nonEmpty) {
          // width is set to a large number to fill the entire width of the page
          tr(s"${showStep show ciStep}" colspan 5 width 8000) +: stepRows
        } else {
          tr(s"${showStep show ciStep} (no steps)" colspan 5 width 8000) :: Nil
        }
    }

    tableRowsToHtml(tableRows)
  }

  def messagesToTableRows(
    messages: Seq[ResultMessage],
    extracted: Extracted,
    project: ProjectRef,
  ): Seq[TableRow] =
    // use HTML breaks for nicer formatting of multiline messages
    messages.map {
      case msg: SuccessMessage =>
        tr(
          "",
          successSubMoji width 40,
          msg.show(extracted, project, code).replaceAll("\n", "<br />"),
        )
      case msg: FailureMessage =>
        tr(
          "",
          failureSubMoji width 40,
          msg.show(extracted, project, code).replaceAll("\n", "<br />"),
        )
      case msg: SkippedMessage =>
        tr(
          "",
          notRunSubMoji width 40,
          msg.show(extracted, project, code).replaceAll("\n", "<br />"),
        )
    }

  def pendingStepsToTableRows(
    steps: Seq[PendingStep],
    verbose: Boolean,
    stepsResult: InternalStepsKeys.StepsResult,
    showPendingStep: Show[PendingStep],
    extracted: Extracted,
  ): Seq[TableRow] = steps.flatMap { step =>
    stepsResult.get(step.project -> step.step) match {
      case Some((_: StepResult.Succeeded, messages: Seq[ResultMessage])) =>
        val ciStepStr = showPendingStep show step
        Seq(
          tr(successMoji title "succeeded" width 40, ciStepStr colspan 2),
        ) ++ messagesToTableRows(messages, extracted, step.project)
      case Some((_: StepResult.Failed, messages)) =>
        val ciStepStr = showPendingStep show step
        // format: off
        Seq(
          tr(failureMoji title "failed" width 40, ciStepStr colspan 2),
        ) ++ messagesToTableRows(messages, extracted, step.project)
        // format: on
      case Some((_: StepResult.Skipped, messages)) =>
        // only show skipped steps in verbose mode
        if (verbose) {
          val ciStepStr = showPendingStep show step
          Seq(
            tr(notRunMoji title "skipped" width 40, ciStepStr colspan 2),
          ) ++ messagesToTableRows(messages, extracted, step.project)
        } else Nil
      case _ =>
        // clarify when an incomplete step will be skipped
        lazy val skipMsg = if (step.willBeSkipped) {
          val reasonStr = StepsUtils.getSkipReason(step).map { reason =>
            s" with reason: ${reason.show(extracted, step.project, code)}"
          } getOrElse ""
          Some(s"Will be skipped$reasonStr")
        } else {
          None
        }
        // only show to be skipped steps in verbose mode
        if (verbose || !step.willBeSkipped) {
          val ciStepStr = showPendingStep show step
          Seq(
            tr(notRunMoji title "not completed" width 40, ciStepStr colspan 2),
          ) ++ skipMsg.map { msg =>
            tr("", notRunSubMoji width 40, msg)
          }
        } else {
          Nil
        }
    }
  }
}
