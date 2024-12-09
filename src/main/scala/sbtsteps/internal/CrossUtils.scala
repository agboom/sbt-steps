package sbtsteps
package internal

import sbt.*
import sbt.Keys.*
import collection.mutable

object CrossUtils {

  type ScalaVersion = String

  /*
   * Copied from [[https://github.com/sbt/sbt/blob/2d7ec47b13e02526174f897cca0aef585bd7b128/main/src/main/scala/sbt/Cross.scala#L96]]
   * @return Configured '''crossScalaVersions''' for the given project, or '''scalaVersion''' as fallback.
   */
  def crossVersions(
    extracted: Extracted,
    proj: ResolvedReference,
  ): Seq[ScalaVersion] = {
    ((proj / crossScalaVersions) get extracted.structure.data) getOrElse {
      // reading scalaVersion is a one-time deal
      ((proj / scalaVersion) get extracted.structure.data).toSeq
    }
  }

  /** Set the Scala version for the given projects. Adapted from
    * [[https://github.com/sbt/sbt/blob/f672cb85a9e7c21a17736a34ab95880b7b5dd320/main/src/main/scala/sbt/Cross.scala#L401]]
    */
  def setScalaVersionForProjects(
    version: ScalaVersion,
    projects: Seq[ResolvedReference],
    state: State,
  ): State = {
    val extracted = Project.extract(state)

    val newSettings = projects.flatMap { project =>
      val scope = Scope(Select(project), Zero, Zero, Zero)
      Seq(scope / scalaVersion := version, scope / scalaHome := None)
    }

    val filterKeys: Set[AttributeKey[?]] =
      Set(scalaVersion, scalaHome).map(_.key)

    val projectsContains: Reference => Boolean = projects.toSet.contains

    // Filter out any old scala version settings that were added, this is just for hygiene.
    val filteredRawAppend = extracted.session.rawAppend.filter(_.key match {
      case Def.ScopedKey(Scope(Select(ref), Zero, Zero, Zero), key)
          if filterKeys.contains(key) && projectsContains(ref) =>
        false
      case _ => true
    })

    val newSession =
      extracted.session.copy(rawAppend = filteredRawAppend ++ newSettings)

    BuiltinCommands.reapply(newSession, extracted.structure, state)
  }

  /** Run an action for the cross Scala versions of multiple projects.
    *
    * @param pendingSteps
    *   The pending steps for this action. The pending steps must have the same
    *   step, but different projects. The cross Scala versions for each pending
    *   steps are evaluated and the action is run both for the right cross Scala
    *   versions for the right project.
    * @param action
    *   A curried function that first accepts a list of steps. For each cross
    *   Scala version, only the steps to which the version applies will be
    *   passed. This is to make sure there are no unnecessary switches.
    * @return
    *   A function that accepts a start state and returns the result containing
    *   a new state and the status for each completed step. If a step failed for
    *   one of the cross Scala versions it is considered failed and
    *   '''succeeded''' is set to false to indicate this.
    */
  def multiProjectCrossBuildToAction[T](
    pendingSteps: Seq[PendingStep],
    action: Seq[PendingStep] => StateStatus => MultiStepResults,
  ): StateStatus => MultiStepResults = { startState: StateStatus =>
    val extracted = Project.extract(startState.state)

    val stepsByVersion =
      mutable.SortedMap.empty[CrossUtils.ScalaVersion, Seq[PendingStep]]

    // build up the list of steps by Scala version
    // while also creating a list of Scala versions by step for later reference
    val versionsByStep = pendingSteps.map { step =>
      val crossVersions = CrossUtils.crossVersions(extracted, step.project)

      crossVersions.foreach { version =>
        val currentSteps = stepsByVersion.getOrElse(version, Nil)
        stepsByVersion += version -> (currentSteps :+ step)
      }

      (step.project, step.step) -> crossVersions
    }.toMap

    val results = stepsByVersion.foldLeft(MultiStepResults(startState, Nil)) {
      case (MultiStepResults(status, stepResults), (version, stepsForVersion))
          if status.continue =>
        val stagedSteps = stepsForVersion.filterNot(_.willBeSkipped)
        val projectStr = if (stepsForVersion.size == 1) {
          "1 project"
        } else {
          s"${stepsForVersion.size} projects"
        }
        status.state.log.info(
          s"Setting Scala version to $version on $projectStr...",
        )
        // run the action only for the steps that have the configured version
        val results = action(stepsForVersion)(
          status.withState(
            CrossUtils.setScalaVersionForProjects(
              version,
              stagedSteps.map(_.project),
              status.state,
            ),
          ),
        )
        // append this action's results to the accumulated results
        MultiStepResults(
          results.status,
          stepResults ++ results.stepResults,
        )
      case (abort, _) =>
        // reached abort state, so drop remaining cross steps
        abort
    }

    // every cross action has zero or one StepResult with a status
    // however, for now we only set one per pending step
    // so we aggregate the cross step results using some intelligence
    val crossStepResults = results.stepResults
      .groupBy(result => result.project -> result.step)
      .flatMap {
        case ((ref, step), crossResults) =>
          crossResults
            // if one of the cross steps failed, we consider the entire step failed
            .find(_.isFailure)
            .orElse {
              if (crossResults.size < versionsByStep(ref -> step).size) {
                // if not all results exists, the cross step has not been completed
                None
              } else {
                // if all results exist (and no failure is found), we assume success
                crossResults.headOption
              }
            }
      }.toSeq

    // undo all appended settings so that (cross) Scala versions are reset
    // but keep the final state
    val finalExtracted = Project.extract(results.status.state)
    val finalState = BuiltinCommands.reapply(
      finalExtracted.session.copy(rawAppend = extracted.session.rawAppend),
      finalExtracted.structure,
      results.status.state,
    )
    results.copy(
      status = results.status.withState(finalState),
      stepResults = crossStepResults,
    )
  }

}
