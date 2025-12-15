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
  def setScalaVersionsForProjects(
    projectVersions: Seq[(ProjectRef, ScalaVersion, Seq[ScalaVersion])],
    state: State,
  ): State = {
    val extracted = Project.extract(state)

    val newSettings = projectVersions.flatMap {
      case (project, version, crossVersions) =>
        val scope = Scope(Select(project), Zero, Zero, Zero)
        Seq(
          scope / scalaVersion := version,
          scope / crossScalaVersions := crossVersions,
          scope / scalaHome := None,
        )
    }

    val filterKeys: Set[AttributeKey[?]] =
      Set(scalaVersion, scalaHome).map(_.key)

    val projectsContains: Reference => Boolean = projectVersions.toSet.contains

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

  /** Adapted from
    * [[https://github.com/sbt/sbt/blame/424d0eb50db3fc6a4c673501ed5a4de7ca7551f2/main/src/main/scala/sbt/Cross.scala#L271]].
    *
    * Behavior is similar to sbt's, except:
    *   - Passing the projects to switch explicitly
    *   - Not considering '''scalaHome''' (only use named Scala versions)
    *   - Forcing Scala versions is not supported
    *   - Logging is a bit different
    */
  def switchScalaVersion(
    requestedVersion: ScalaVersion,
    projects: Seq[ProjectRef],
    state: State,
    verbose: Boolean,
  ): State = {
    def logSwitchInfo(
      included: Seq[(ProjectRef, ScalaVersion, Seq[ScalaVersion])],
      excluded: Seq[(ProjectRef, Seq[ScalaVersion])],
    ) = {
      included
        .groupBy(_._2)
        .foreach {
          case (selectedVersion, projects) =>
            val projectStr = projects.size match {
              case 1 => "1 project"
              case size => s"$size projects"
            }
            // verbose mode logs detailed info
            if (!verbose) {
              state.log.info(
                s"Setting Scala version to $selectedVersion on $projectStr.",
              )
            }
        }
      if (excluded.nonEmpty && !verbose) {
        val projectStr = excluded.size match {
          case 1 => "1 project"
          case size => s"$size projects"
        }
        state.log.info(
          s"Not switching Scala version on $projectStr, re-run with -v for details.",
        )
      }

      def detailedLog(msg: => String) =
        if (verbose) state.log.info(msg) else state.log.debug(msg)

      def logProject(ref: ProjectRef, scalaVersions: Seq[ScalaVersion]): Unit =
        detailedLog(
          s"- ${ref.project} ${scalaVersions.mkString("(", ", ", ")")}",
        )
      if (included.nonEmpty) {
        detailedLog("Switching Scala version on:")
        included.foreach { case (project, scalaVersion, scalaVersions) =>
          logProject(
            project,
            scalaVersions.map { v =>
              if (v == scalaVersion) s"*$v*" else v
            },
          )
        }
      }
      if (excluded.nonEmpty) {
        detailedLog("Not switching Scala version on:")
        excluded.foreach { case (project, scalaVersions) =>
          logProject(project, scalaVersions)
        }
      }
    }

    val extracted = Project.extract(state)
    val projectsAndScalaVersion = projects
      .map { proj => proj -> crossVersions(extracted, proj) }
      .map {
        case (project, scalaVersions) =>
          val selector = SemanticSelector(requestedVersion)
          scalaVersions.filter(v =>
            selector.matches(VersionNumber(v)),
          ) match {
            case Seq(version) =>
              (project, Some(version), scalaVersions)
            case Nil =>
              // fall back to bincompat version from `crossScalaVersions` like sbt
              val svOpt = scalaVersions.find(
                CrossVersion.isScalaBinaryCompatibleWith(
                  newVersion = requestedVersion,
                  _,
                ),
              )
              svOpt.foreach { sv =>
                state.log.info(
                  s"Falling back '${project.project}' to listed '$sv' instead of unlisted '$requestedVersion'",
                )
              }
              (project, svOpt, scalaVersions)
            case multiple =>
              throw new MessageOnlyException(
                s"Multiple crossScalaVersions matched '$requestedVersion' in '${project.project}': ${multiple.mkString(", ")}",
              )
          }
      }

    val included = projectsAndScalaVersion.collect {
      case (project, Some(version), scalaVersions) =>
        (project, version, scalaVersions)
    }
    val excluded = projectsAndScalaVersion.collect {
      case (project, None, scalaVersions) => project -> scalaVersions
    }

    logSwitchInfo(included, excluded)

    setScalaVersionsForProjects(included, state)
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
    verbose: Boolean,
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
        lazy val stagedSteps = stepsForVersion.filterNot(_.willBeSkipped)
        val projectsToSwitch = resolveDependencies(
          stagedSteps.map(_.project),
          extracted,
        )
        // run the action only for the steps that have the configured version
        val results = action(stepsForVersion)(
          status.withState(
            switchScalaVersion(
              version,
              projectsToSwitch,
              status.state,
              verbose,
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

  /**
    * Recursively get inter-project dependencies of the given projects.
    * @return List of unique projects that transitively depend on the given projects.
    */
  def resolveDependencies(
    projects: Seq[ProjectRef],
    extracted: Extracted,
  ): Seq[ProjectRef] = {
    def findDependencies(project: ProjectRef): Seq[ProjectRef] = {
      project :: (extracted.structure
        .allProjects(project.build)
        .find(_.id == project.project) match {
        case Some(resolved) =>
          resolved
            .dependencies
            .toList
            .map(_.project)
            .flatMap(findDependencies)
        case None => Nil
      })
    }

    projects.flatMap(findDependencies).distinct
  }

}
