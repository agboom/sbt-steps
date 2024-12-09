package sbtsteps
package internal

import sbt.*

import model.*

object ASCIIUtils {
  private val maxColumnDefault = 300

  /** Builds an ASCII tree of the given steps grouped by step for printing.
    * @param pendingStepsByStep
    *   The aggregated steps grouped by step.
    * @param verbose
    *   Whether to include skipped steps and more step fields in the tree.
    * @param state
    *   Needed to print a task key.
    * @param completedStatus
    *   If defined, the completed status with messages of each step is shown. If
    *   not, the status is not shown.
    * @param maxColumn
    *   Maximum width of each row.
    * @return
    *   A multiline string containing the ASCII tree.
    */
  def pendingStepsByStepToASCIITree(
    pendingStepsByStep: Seq[(Step, Seq[PendingStep])],
    verbose: Boolean,
    state: State,
    completedStatus: Option[InternalStepsKeys.StepsResult] = None,
    maxColumn: Int = maxColumnDefault,
  ): String = {
    pendingStepsByStep.map {
      case (step, pendingSteps) =>
        ciStepToASCIITree(
          Left(step),
          pendingSteps,
          verbose,
          completedStatus,
          state,
          maxColumn,
        )
    }.mkString("\n")
  }

  /** Builds an ASCII tree of the given steps grouped by project name for
    * printing.
    * @param pendingStepsByProject
    *   The aggregated steps grouped by project.
    * @param verbose
    *   Whether to include skipped steps and more step fields in the tree.
    * @param state
    *   Needed to print a task key.
    * @param completedStatus
    *   If defined, the completed status with messages of each step is shown. If
    *   not, the status is not shown.
    * @param maxColumn
    *   Maximum width of each row.
    * @return
    *   A multiline string containing the ASCII tree.
    */
  def pendingStepsByProjectToASCIITree(
    pendingStepsByProject: Seq[(ProjectRef, Seq[PendingStep])],
    verbose: Boolean,
    state: State,
    completedStatus: Option[InternalStepsKeys.StepsResult] = None,
    maxColumn: Int = maxColumnDefault,
  ): String = {
    pendingStepsByProject.map {
      case (projectRef, pendingSteps) =>
        ciStepToASCIITree(
          Right(projectRef),
          pendingSteps,
          verbose,
          completedStatus,
          state,
          maxColumn,
        )
    }.mkString("\n")
  }

  private def ciStepToASCIITree(
    ciStepOrProjectName: Either[Step, ProjectRef],
    pendingSteps: Seq[PendingStep],
    verbose: Boolean,
    completedStatus: Option[InternalStepsKeys.StepsResult],
    state: State,
    maxColumn: Int = maxColumnDefault,
  ): String = {
    sbt.internal.Graph.toAscii[TreeItem](
      top = ciStepOrProjectName match {
        case Left(ciStep) =>
          StepItem(ciStep, pendingSteps, verbose, completedStatus, state)
        case Right(ProjectRef(_, projectName)) =>
          ProjectItem(
            projectName,
            pendingSteps,
            verbose,
            completedStatus,
            state,
          ),
      },
      children = _.children,
      display = _.display,
      maxColumn = maxColumn,
    )
  }

  /** Print a single pending step as ASCII tree. Used during CI run to print an
    * individual step before running it.
    *
    * @param pendingStep
    *   The step to print
    * @param verbose
    *   Whether to include skipped steps and more step fields in the tree.
    * @param includeRootProjectName
    *   If true, shows the root project name for both tasks and commands. For
    *   runs grouped by step this is true, for runs grouped by project this is
    *   false.
    * @param state
    *   Needed to print a task key.
    * @param completedStatus
    *   If defined, the completed status with messages of each step is shown. If
    *   not, the status is not shown.
    * @param scalaVersion
    *   If set, the Scala version will be printed as field instead of the "cross
    *   build" field. Usually used while executing the step.
    * @param maxColumn
    *   Maximum width of each row.
    * @return
    *   A multiline string containing the ASCII tree.
    */
  def pendingStepToASCIITree(
    pendingStep: PendingStep,
    verbose: Boolean,
    includeRootProjectName: Boolean,
    state: State,
    completedStatus: Option[InternalStepsKeys.StepsResult] = None,
    scalaVersion: Option[String] = None,
    maxColumn: Int = maxColumnDefault,
  ): String = {
    sbt.internal.Graph.toAscii[TreeItem](
      top = EmptyItem,
      children = {
        case EmptyItem =>
          PendingStepItemByProject(
            pendingStep,
            verbose,
            completedStatus,
            state,
            scalaVersion = scalaVersion,
            includeRootProjectName = includeRootProjectName,
          ) :: Nil
        case item =>
          item.children
      },
      display = _.display,
      maxColumn = maxColumn,
    )
  }
}
