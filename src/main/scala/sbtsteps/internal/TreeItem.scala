package sbtsteps
package internal

import sbt.{internal as _, *}

import scala.io.AnsiColor

import model.*

sealed trait TreeItem extends Product with Serializable {

  /** A string representation of this node.
    */
  def display: String

  /** The children of this node.
    */
  def children: Seq[TreeItem]
}

case object EmptyItem extends TreeItem {
  override def display: String = ""

  override def children: Seq[TreeItem] = Nil
}

/** Super type of a root node that contains common field items.
  */
abstract class BaseStepItem(
  step: Step,
  verbose: Boolean,
  state: State,
) extends TreeItem {

  protected lazy val nameFieldItem = step.name.map(KeyValueItem("name", _))

  protected lazy val continueFieldItem =
    if (step.alwaysContinue || verbose) {
      Some(KeyValueItem("continue on error", s"${step.alwaysContinue}"))
    } else {
      None
    }

  protected lazy val crossBuildFieldItem =
    if (step.crossBuild || verbose) {
      Some(KeyValueItem("cross build", s"${step.crossBuild}"))
    } else {
      None
    }

  protected lazy val runOnceFieldItem =
    if (step.runOnce || verbose) {
      Some(KeyValueItem("run once", s"${step.runOnce}"))
    } else {
      None
    }

  protected lazy val projectFilterFieldItem = step.projectFilter match {
    case ThisProject if !verbose =>
      None
    case otherFilter =>
      Some(KeyValueItem("project filter", s"$otherFilter"))
  }
}

/** Root node of the tree that displays a configured step and its underlying
  * pending steps.
  */
case class StepItem(
  step: Step,
  pendingSteps: Seq[PendingStep],
  verbose: Boolean,
  completedStatus: Option[InternalStepsKeys.StepsResult],
  state: State,
) extends BaseStepItem(step, verbose, state) {

  private lazy val pendingStepsFieldItem = {
    val stepsToShow = pendingSteps.collect {
      // only include skipped steps if verbose is true
      case pendingStep if verbose || !pendingStep.willBeSkipped =>
        PendingStepItemByStep(pendingStep, verbose, completedStatus, state)
    }

    // only show this item if there are actual steps to be shown
    if (stepsToShow.nonEmpty) {
      Some(ListItem(ValueItem(s"project steps:"), stepsToShow*))
    } else {
      None
    }
  }

  override lazy val children: Seq[FieldItem] = {
    nameFieldItem ++ crossBuildFieldItem ++ runOnceFieldItem ++
      projectFilterFieldItem ++ continueFieldItem ++ pendingStepsFieldItem
  }.toSeq

  private lazy val extracted = Project.extract(state)

  private lazy val showStep: Show[Step] = StepsUtils.createShowStep(
    extracted,
    StepsUtils.ShowKeyFactory(
      // at this level, don't show the project name
      scopeMask =
        ScopeMask(project = false, config = true, task = true, extra = false),
    ),
  )

  override lazy val display: String = {
    if (pendingSteps.isEmpty || pendingSteps.forall(_.willBeSkipped)) {
      s"${step.stepType}: ${showStep show step} (no staged steps)"
    } else {
      s"${step.stepType}: ${showStep show step}"
    }
  }
}

/** Root node of the tree that displays a project and its underlying pending
  * steps.
  */
case class ProjectItem(
  projectName: String,
  pendingSteps: Seq[PendingStep],
  verbose: Boolean,
  completedStatus: Option[InternalStepsKeys.StepsResult],
  state: State,
) extends TreeItem {

  override lazy val children: Seq[TreeItem] = pendingSteps.collect {
    // only include skipped steps if verbose is true
    case pendingStep if verbose || !pendingStep.willBeSkipped =>
      PendingStepItemByProject(pendingStep, verbose, completedStatus, state)
  }

  override lazy val display: String = {
    if (pendingSteps.isEmpty || pendingSteps.forall(_.willBeSkipped)) {
      s"project: $projectName (no staged steps)"
    } else {
      s"project: $projectName"
    }
  }
}

/** Base class for a tree item that shows a pending step, either grouped by
  * project or by step.
  */
abstract class PendingStepItem(
  pendingStep: PendingStep,
  verbose: Boolean,
  completedStatus: Option[InternalStepsKeys.StepsResult],
  state: State,
) extends BaseStepItem(pendingStep.step, verbose, state) {
  private lazy val extracted = Project.extract(state)

  private def messagesToTreeItems(
    messages: Seq[ResultMessage],
    surround: Surround,
  ): Seq[TreeItem] =
    messages.map { msg =>
      msg.show(extracted, pendingStep.project, surround)
        .linesIterator.toList match {
        case Nil =>
          EmptyItem
        case head :: tail =>
          ListItem(
            ValueItem(head),
            tail.map(ValueItem(_))*,
          )
      }
    }

  protected lazy val statusFieldItem = completedStatus
    // only show completed status if completedStatus is defined
    .map(_.get(pendingStep.project -> pendingStep.step) map {
      case (_: StepResult.Succeeded, messages) =>
        ListItem(
          KeyValueItem(
            "status",
            s"${AnsiColor.GREEN}succeeded${AnsiColor.RESET}",
          ),
          messagesToTreeItems(
            messages,
            Surround(AnsiColor.BOLD, AnsiColor.RESET),
          )*,
        )
      case (_: StepResult.Skipped, messages) =>
        ListItem(
          KeyValueItem("status", s"${AnsiColor.BOLD}skipped${AnsiColor.RESET}"),
          messagesToTreeItems(
            messages,
            Surround(AnsiColor.BOLD, AnsiColor.RESET),
          )*,
        )
      case (_: StepResult.Failed, messages) =>
        ListItem(
          KeyValueItem("status", s"${AnsiColor.RED}failed${AnsiColor.RESET}"),
          messagesToTreeItems(
            messages,
            Surround(AnsiColor.RED, AnsiColor.RESET),
          )*,
        )
    } getOrElse KeyValueItem("status", "not completed"))

  // only show skip reason if status is not displayed
  // status will display skip reason as well
  protected lazy val skippedReasonFieldItem = if (statusFieldItem.isEmpty) {
    StepsUtils.getSkipReason(pendingStep)
      .map(reason =>
        KeyValueItem("skipped", reason.show(extracted, pendingStep.project)),
      )
  } else {
    None
  }
}

/** Intermediate node of the tree that displays pending step information as if
  * grouped by step.
  */
case class PendingStepItemByStep(
  pendingStep: PendingStep,
  verbose: Boolean,
  completedStatus: Option[InternalStepsKeys.StepsResult],
  state: State,
) extends PendingStepItem(pendingStep, verbose, completedStatus, state) {

  private lazy val extracted: Extracted = Project.extract(state)

  private lazy val showPendingStep: Show[PendingStep] =
    StepsUtils.createShowPendingStep(
      extracted,
      includeRootProjectName = true,
    )

  override lazy val children: Seq[FieldItem] = {
    statusFieldItem ++ skippedReasonFieldItem
  }.toSeq

  override lazy val display: String = {
    s"${pendingStep.stepType}: ${showPendingStep show pendingStep}"
  }

}

/** Intermediate node of the tree that displays pending step information as if
  * grouped by project.
  */
case class PendingStepItemByProject(
  pendingStep: PendingStep,
  verbose: Boolean,
  completedStatus: Option[InternalStepsKeys.StepsResult],
  state: State,
  scalaVersion: Option[String] = None,
  includeRootProjectName: Boolean = false,
) extends PendingStepItem(pendingStep, verbose, completedStatus, state) {

  private lazy val extracted: Extracted = Project.extract(state)

  private lazy val showPendingStep: Show[PendingStep] =
    StepsUtils.createShowPendingStep(
      extracted,
      includeRootProjectName = includeRootProjectName,
    )

  private lazy val scalaVersionOrCrossBuildFieldItem = scalaVersion
    .map(KeyValueItem("Scala version", _))
    .orElse(crossBuildFieldItem)

  override lazy val children: Seq[FieldItem] = {
    nameFieldItem ++ scalaVersionOrCrossBuildFieldItem ++
      runOnceFieldItem ++ projectFilterFieldItem ++
      continueFieldItem ++ statusFieldItem ++ skippedReasonFieldItem
  }.toSeq

  override lazy val display: String = {
    s"${pendingStep.stepType}: ${showPendingStep show pendingStep}"
  }
}

/** Data type for different containers for fields, such as value, key+value and
  * list.
  */
sealed trait FieldItem extends TreeItem

/** End node of the tree displayed as single "value" item
  */
case class ValueItem(value: String) extends FieldItem {
  override lazy val display: String = value

  override lazy val children: Seq[TreeItem] = Nil
}

/** End node of the tree displayed as "key: value" pair
  */
case class KeyValueItem(key: String, value: String) extends FieldItem {
  override lazy val display: String = s"$key: $value"

  override lazy val children: Seq[TreeItem] = Nil
}

/** Recursive field item containing a parent item and children items.
  *
  * @param parent
  *   The parent item will be displayed as single tree item.
  * @param children
  *   A list of items displayed as children of the parent item.
  * @example
  *   {{{
  * ListItem(KeyValueItem("key", "value"), ValueItem("foo"), ValueItem("bar"))
  * // displayed as
  * +-key: value
  *   +-foo
  *   +-bar
  *   }}}
  */
case class ListItem(parent: TreeItem, children: TreeItem*) extends FieldItem {
  override def display: String = parent.display
}
