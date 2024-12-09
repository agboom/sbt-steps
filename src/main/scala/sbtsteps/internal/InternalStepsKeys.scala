package sbtsteps
package internal

import sbt.*

import model.*

object InternalStepsKeys {

  final lazy val pendingStepsByProject =
    taskKey[Seq[(ProjectRef, Seq[PendingStep])]](
      "Aggregated list of pending steps grouped by project, either staged to run, or to be skipped.",
    )

  final lazy val pendingStepsByStep = taskKey[Seq[(Step, Seq[PendingStep])]](
    "Aggregated list of pending steps grouped by step, either staged to run, or to be skipped.",
  )

  final type StepsResult =
    Map[(ProjectRef, Step), (StepResult, Seq[ResultMessage])]

  // used to track the completed result during and after run
  final lazy val stepsResultAttr =
    AttributeKey[Map[AttributeKey[?], StepsResult]](
      "stepsResult",
      rank = KeyRanks.Invisible,
    )

  // short hand task to get results for a specific steps scope
  final lazy val stepsResult =
    taskKey[StepsResult]("Step results and messages per pending step id")

  /*
   * Helpers for assertions in scripted tests
   */
  type StepsTestTuple[T] = (T, Option[Boolean], Seq[ResultMessage])
  type StepsTestTuples[A, B] = (A, Seq[StepsTestTuple[B]])
  type StepsTestTuplesByProject = StepsTestTuples[ProjectRef, Step]
  type StepsTestTuplesByStep = StepsTestTuples[Step, ProjectRef]

  def toTestTuplesByProject(
    pendingSteps: Seq[(ProjectRef, Seq[PendingStep])],
    stepsResult: StepsResult,
  ): Seq[StepsTestTuplesByProject] =
    toTestTuples(pendingSteps, stepsResult, _.step)

  def toTestTuplesByStep(
    pendingSteps: Seq[(Step, Seq[PendingStep])],
    stepsResult: StepsResult,
  ): Seq[StepsTestTuplesByStep] =
    toTestTuples(pendingSteps, stepsResult, _.project)

  def toTestTuples[A, B](
    pendingSteps: Seq[(A, Seq[PendingStep])],
    stepsResult: StepsResult,
    fn: PendingStep => B,
  ): Seq[StepsTestTuples[A, B]] =
    pendingSteps.map {
      case (grouping, steps) => grouping -> steps.map { step =>
          val (isSuccess, msgs) =
            stepsResult.get(step.project -> step.step).map {
              case (result, msgs) =>
                // step is completed, indicate with Some
                // include result messages
                Some(!result.isFailure) -> msgs
            }.getOrElse(
              // step is not completed, indicate with None
              // include skip reason if skipped step
              None -> StepsUtils.getSkipReason(step).toSeq,
            )
          (fn(step), isSuccess, msgs)
        }
    }
}
