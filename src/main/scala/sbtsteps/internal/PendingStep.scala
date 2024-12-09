package sbtsteps
package internal

import sbt.*

import model.*

/** Step that may or may not be run. Used as intermediate datatype to aggregate
  * steps in the build definition. The configured step is in [[model.Step]].
  */
sealed trait PendingStep extends Product with Serializable {
  val step: Step

  final val stepType: String = step.stepType

  final val crossBuild: Boolean = step.crossBuild

  final val runOnce: Boolean = step.runOnce

  final val projectFilter: ProjectReference = step.projectFilter

  final val alwaysContinue: Boolean = step.alwaysContinue

  /** The project that this pending step is assigned to.
    */
  def project: ProjectRef

  /** If true, this step will be skipped upon run.
    */
  def willBeSkipped: Boolean
}

/** Step that is staged to run.
  *
  * @param step
  *   The step that is staged to run.
  * @param projectRef
  *   The project that this step belongs to.
  */
final case class StagedStep(
  step: Step,
  project: ProjectRef,
) extends PendingStep {
  override def willBeSkipped: Boolean = false
}

/** Step that will be skipped
  *
  * @param step
  *   The step that will be skipped.
  * @param skipReason
  *   The reason for skipping this step.
  * @param projectRef
  *   The project that this step belongs to.
  */
final case class SkippedStep(
  step: Step,
  skipReason: SkippedMessage,
  project: ProjectRef,
) extends PendingStep {
  override def willBeSkipped: Boolean = true
}
