package sbtsteps
package internal

import sbt.*
import model.*

/** Data type to pass the results of a multi step action around
  * @param state
  *   The state as a result of the action.
  * @param succeeded
  *   True if there are no failed steps.
  * @param stepResults
  *   A list containing a [[model.StepResult]] for each completed step
  *   accompanied with its [[model.ResultMessage]]s.
  */
case class MultiStepResults(
  status: StateStatus,
  stepResults: Seq[StepResult],
)

sealed trait StateStatus {
  type Self <: StateStatus
  def state: State
  def continue: Boolean
  def withState(state: State): Self
}

object StateStatus {
  case class NoErrors(state: State) extends StateStatus {
    type Self = NoErrors
    def continue: Boolean = true
    def withState(state: State): NoErrors = copy(state = state)
  }
  case class ContinuedWithErrors(state: State) extends StateStatus {
    type Self = ContinuedWithErrors
    def continue: Boolean = true
    def withState(state: State): ContinuedWithErrors = copy(state = state)
  }
  case class AbortedWithErrors(state: State) extends StateStatus {
    type Self = AbortedWithErrors
    def continue: Boolean = false
    def withState(state: State): AbortedWithErrors = copy(state = state)
  }
}

/** Container type for [[model.MessageBuilder]]s for each result type. Use
  * [[model.MessageBuilder.getMessages]] to extract the messages out of a
  * result.
  */
case class MessageBuilders(
  forSuccess: Seq[MessageBuilder[StepResult.Succeeded, ResultMessage]],
  forFailure: Seq[MessageBuilder[StepResult.Failed, ResultMessage]],
  forSkipped: Seq[MessageBuilder[StepResult.Skipped, ResultMessage]],
)
