package sbtsteps
package model

import sbt.*

/** Data type containing the result of a completed [[model.Step]].
  *
  * It can be one of [[StepResult.Succeeded]], [[StepResult.Skipped]] or
  * [[StepResult.Failed]].
  */
sealed trait StepResult extends Product with Serializable {

  /** @return
    *   The completed [[Step]].
    */
  val step: Step

  /** @return
    *   The project that this step has run on.
    */
  val project: ProjectRef

  /** @return
    *   True if this step has failed.
    */
  val isFailure: Boolean
}

object StepResult {

  /** A [[Step]] that has completed successfully.
    * @param value
    *   The value that the [[Step]] returned.
    */
  final case class Succeeded(value: Any, step: Step, project: ProjectRef)
      extends StepResult {
    val isFailure: Boolean = false
  }

  /** A [[Step]] that has been skipped.
    */
  final case class Skipped(
    reason: SkippedMessage,
    step: Step,
    project: ProjectRef,
  ) extends StepResult {
    val isFailure: Boolean = false
  }

  /** A [[Step]] that failed to complete.
    * @param error
    *   [[sbt.Incomplete]] returned when running a task.
    */
  final case class Failed(error: Incomplete, step: Step, project: ProjectRef)
      extends StepResult {
    val isFailure: Boolean = true
  }
}
