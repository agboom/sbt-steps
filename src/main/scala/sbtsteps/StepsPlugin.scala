package sbtsteps

import sbt.Keys.*
import sbt.complete.DefaultParsers.*
import sbt.complete.Parser
import sbt.internal.util.Dag
import sbt.{internal as _, some as _, *}

import scala.collection.mutable
import scala.util.*

import internal.*

object StepsPlugin extends AutoPlugin {

  object autoImport extends StepsKeys

  import autoImport.*

  sealed private trait Output

  private object Output {
    final case class File(file: sbt.File) extends Output

    final case object Stdout extends Output
  }

  private def outputParser(base: File): Parser[Output] =
    '-'.map(_ => Output.Stdout) | fileParser(base).map(Output.File)

  private lazy val verboseFlag = cli.Flag('v', "verbose")
  private lazy val statusFlag = cli.Flag('s', "status")
  private def outputArg(base: File, defaultFile: File) = cli.PosArg(
    "output",
    outputParser(base),
  ) withDefault Output.File(defaultFile)

  override lazy val trigger: PluginTrigger = allRequirements

  override lazy val globalSettings: Seq[Def.Setting[?]] =
    Def.settings(
      steps := Nil,
      stepsGrouping := StepsGrouping.ByStep,
      stepsMessagesForSuccess := Nil,
      stepsMessagesForFailure := MessageBuilder.forFailureDefault :: Nil,
      stepsMessagesForSkipped := MessageBuilder.forSkippedDefault :: Nil,
    )

  private def camelCaseToKebabCase(str: String): String = str.foldLeft("") {
    case (str, char) if char.isUpper => s"$str-${char.toLower}"
    case (str, char) => s"$str$char"
  }

  def stepsSettings(key: InputKey[StateTransform]): Seq[Def.Setting[?]] =
    Def.settings(
      key := stepsInputTask(key).evaluated,
      key / name := key.key.label,
      key / stepsStatusReportFileName := s"${camelCaseToKebabCase(key.key.label)}-status.html",
      key / aggregate := false,
      key / stepsTree / aggregate := false,
      key / stepsTree := {
        val stateValue = state.value
        val log = streams.value.log
        val (verbose, showStatus) =
          cli.CLIParser(verboseFlag, statusFlag).parsed
        val completedStatus = if (showStatus)
          Some((key / InternalStepsKeys.stepsResult).value)
        else None
        (key / stepsGrouping).value match {
          case StepsGrouping.ByStep =>
            val steps = (key / InternalStepsKeys.pendingStepsByStep).value
            log.info(ASCIIUtils.pendingStepsByStepToASCIITree(
              steps,
              verbose,
              stateValue,
              completedStatus,
            ))
          case StepsGrouping.ByProject =>
            val steps = (key / InternalStepsKeys.pendingStepsByProject).value
            log.info(ASCIIUtils.pendingStepsByProjectToASCIITree(
              steps,
              verbose,
              stateValue,
              completedStatus,
            ))
        }
      },
      key / stepsStatusReport / aggregate := false,
      key / stepsStatusReport := {
        val (verbose, output) = cli.CLIParser(
          verboseFlag,
          outputArg(
            (LocalRootProject / baseDirectory).value,
            (LocalRootProject / target)
              .value / (key / stepsStatusReportFileName).value,
          ),
        ).parsed

        reportStatus(key, verbose, output, state.value)
      },
      key / InternalStepsKeys.stepsResult / aggregate := false,
      key / InternalStepsKeys.stepsResult := {
        state.value.get(InternalStepsKeys.stepsResultAttr)
          .flatMap(_.get(key.key))
          .getOrElse(Map.empty)
      },
      key / InternalStepsKeys.pendingStepsByProject / aggregate := false,
      key / InternalStepsKeys
        .pendingStepsByProject := pendingStepsByProjectTask(key).value,
      key / InternalStepsKeys.pendingStepsByStep / aggregate := false,
      key / InternalStepsKeys.pendingStepsByStep := pendingStepsByStepTask(
        key,
      ).value,
    )

  private def reportStatus(
    key: InputKey[StateTransform],
    verbose: Boolean,
    output: Output,
    state: State,
  ): String = {
    val extracted = Project.extract(state)
    val (_, stepsResult) =
      extracted.runTask(key / InternalStepsKeys.stepsResult, state)

    val report = extracted.get(key / stepsGrouping) match {
      case StepsGrouping.ByStep =>
        val (_, steps) =
          extracted.runTask(key / InternalStepsKeys.pendingStepsByStep, state)
        HTMLUtils.pendingStepsByStepToHtml(
          steps,
          verbose,
          stepsResult,
          extracted,
        )
      case StepsGrouping.ByProject =>
        val (_, steps) = extracted.runTask(
          key / InternalStepsKeys.pendingStepsByProject,
          state,
        )
        HTMLUtils.pendingStepsByProjectToHtml(
          steps,
          verbose,
          stepsResult,
          extracted,
        )
    }
    val defaultFileName = extracted.get(key / stepsStatusReportFileName)
    output match {
      case Output.File(fileOrDir) =>
        val file = if (fileOrDir.isDirectory) {
          state.log.info(
            s"$fileOrDir is a directory, using ${fileOrDir / defaultFileName}",
          )
          fileOrDir / defaultFileName
        } else {
          fileOrDir
        }
        if (file.exists()) {
          state.log.debug(s"File $file already exists, overwriting...")
        }
        IO.write(file, report)
      case Output.Stdout =>
        state.log.info(report)
    }
    report
  }

  /** Aggregates all steps in the build definition to a list of pending steps
    * per project.
    */
  private def pendingStepsByProjectTask(
    key: InputKey[StateTransform],
  ) = Def.task {
    val stateValue = state.value
    lazy val extracted = Project.extract(stateValue)

    // list to track the steps that should only run once
    val stepsRunOnce: mutable.ListBuffer[Step] = mutable.ListBuffer.empty

    implicit val showKey: Show[ScopedKey[?]] =
      StepsUtils.defaultShowKey(extracted)
    implicit val showProjectFilter: Show[ProjectReference] = _.toString
    // create pending steps from the project steps
    // .all uses a Set under the hood so the projects are unsorted
    // use sbt's ProjectRef Ordering for predictable sorting
    (key / thisProjectRef zip key / steps)
      .all(ScopeFilter(projects = inAnyProject))
      .value
      .sortBy(_._1).map {
        case (ref, steps) => ref -> steps.map { step =>
            val (pendingStep, runOnce) =
              applyProjectFilter(ref, stateValue)(step)
                .fold(_ -> None, toPendingStep(ref, stateValue, stepsRunOnce))
            stepsRunOnce ++= runOnce
            pendingStep
          }
      }
  }

  /** Aggregates all steps in the build definition to a list of pending steps per
    * step. The order of the aggregated steps is determined by topologically
    * sorting all project steps. Any cycles in the graph will result in an
    * error.
    * @example
    *   A steps configuration of:
    *   {{{
    *   project: steps
    *   foo:     [ test, publish, doc ]
    *   bar:     [ versionCheck, publish ]
    *   }}}
    *   is seen as the following directed acyclic graph:
    *   {{{
    *   versionCheck -> (       )
    *                   (publish)
    *           test -> (       ) -> doc
    *   }}}
    *   and will result in the following aggregated order:
    *   {{{
    *   [ test, versionCheck, publish, doc ]
    *   }}}
    *   See the scripted tests for more examples.
    */
  private def pendingStepsByStepTask(
    key: InputKey[StateTransform],
  ) = Def.task {
    val stateValue = state.value
    lazy val extracted = Project.extract(stateValue)

    // retrieve the steps for all projects in this task scope
    // .all uses a Set under the hood so the projects are unsorted
    // use sbt's ProjectRef Ordering for predictable sorting
    val projectSteps: Seq[(ProjectRef, Seq[Step])] =
      (key / thisProjectRef zip key / steps)
        .all(ScopeFilter(projects = inAnyProject))
        .value
        .sortBy(_._1)

    // we lose the project refs when we topologically sort the steps
    // we use this map to look up the project steps for a step
    val groupedSteps: Map[Step, Seq[(ProjectRef, Step)]] = {
      for {
        (ref, steps) <- projectSteps
        step <- steps
      } yield ref -> step
    }.groupBy(_._2)

    // gets the dependencies of each step in the list
    // a dependency is the same as a predecessor of the directed acyclic graph
    // the list should be passed in reverse
    @annotation.tailrec
    def dependencies(
      reverseSteps: List[Step],
      stepDeps: Map[Step, Seq[Step]] = Map.empty,
    ): Map[Step, Seq[Step]] =
      reverseSteps match {
        case Nil =>
          stepDeps
        case step :: Nil =>
          // last step doesn't have any added dependencies
          stepDeps + (step -> stepDeps.getOrElse(step, Seq.empty))
        case step :: nextStep :: remaining =>
          // append nextStep as dependency to step
          val currentDependencies = stepDeps.getOrElse(step, Seq.empty)
          dependencies(
            nextStep :: remaining,
            stepDeps + (step -> (currentDependencies :+ nextStep)),
          )
      }

    val stepDependencies = projectSteps.foldLeft(Map.empty[Step, Seq[Step]]) {
      case (accDependencies, (_, steps)) =>
        // we need to reverse steps, because predecessors are dependencies of successors
        // but sbt's topological sort sorts them the other way around
        dependencies(steps.reverse.toList, accDependencies)
    }
    val sortedSteps =
      try {
        Dag.topologicalSort(stepDependencies.keys)(stepDependencies.getOrElse(
          _,
          Set.empty,
        ))
      } catch {
        case cyclic: Dag.Cyclic =>
          val showStep = StepsUtils.createShowStep(extracted)
          val showRefStep = (ref: ProjectRef, step: Step) =>
            s"- ${step.stepType} ${showStep show step} in project ${ref.project}"

          // get the project steps that are causing the cycle and print them in a nice way
          val stepsLines = groupedSteps(cyclic.value.asInstanceOf[Step])
            .groupBy(_._1)
            .flatMap {
              case (_, steps) =>
                steps.map(showRefStep.tupled)
            }.toList

          val lines =
            "One or more steps are configured in a way that causes a cycle in the aggregated steps:" :: stepsLines
          throw new MessageOnlyException(lines.mkString("\n"))
      }

    // list to track the steps that should only run once
    val stepsRunOnce: mutable.ListBuffer[Step] = mutable.ListBuffer.empty

    // create the pending steps based on the sorted steps
    sortedSteps.map {
      case step =>
        step -> groupedSteps(step).map {
          case (ref, _) =>
            val (pendingStep, runOnce) =
              applyProjectFilter(ref, stateValue)(step)
                .fold(_ -> None, toPendingStep(ref, stateValue, stepsRunOnce))
            stepsRunOnce ++= runOnce
            pendingStep
        }
    }
  }

  /** Apply the step's project filter.
    * @return
    *   '''Right(step)''' if the filter matches or '''Left(skipped)''' if it
    *   doesn't.
    */
  private def applyProjectFilter(
    ref: ProjectRef,
    state: State,
  )(step: Step): Either[SkippedStep, Step] = {
    val extracted = Project.extract(state)
    step.projectFilter match {
      case ThisProject =>
        Right(step)
      case LocalRootProject
          if ref.project == extracted.rootProject(
            extracted.structure.extra.root,
          ) =>
        Right(step)
      case RootProject(build) if ref.project == extracted.rootProject(build) =>
        Right(step)
      case LocalProject(project) if ref.project == project =>
        // TODO: compare build in extracted and ref
        Right(step)
      case otherRef: ProjectRef if ref == otherRef =>
        Right(step)
      case _ =>
        Left(
          SkippedStep(
            step,
            SkippedMessage.ProjectFilter(step.projectFilter, ref),
            ref,
          ),
        )
    }
  }

  /** Create pending step from the given step and project.
    * @param ref
    *   The project that this step applies to
    * @param stepsRunOnce
    *   List of steps with the run-once flag that are already staged to run
    * @param step
    *   The step to convert
    * @return
    *   A tuple of:
    *   - '''StagedStep''' if step is ready to run, or '''SkippedCIStep''' if
    *     some skip predicate holds,
    *   - '''Some(step)''' if the step has the run-once flag and was not yet in
    *     stepsRunOnce, or '''None''' if the step is already in stepsRunOnce (or
    *     doesn't have the flag).
    *
    * @note
    *   Be sure to update stepsRunOnce with the resulting `Option[Step]`.
    */
  private def toPendingStep(
    ref: ProjectRef,
    state: State,
    stepsRunOnce: Seq[Step],
  )(step: Step): (PendingStep, Option[Step]) = {
    val extracted = Project.extract(state)

    step match {
      case step if step.runOnce && stepsRunOnce.contains(step) =>
        // step is configured to run once and already staged to run, so skip this one
        SkippedStep(step, SkippedMessage.RunOnce, ref) -> None
      case step if step.runOnce =>
        // step is configured to run once and not yet staged to run
        // stage this step and add it to the stepsRunOnce list (via return value)
        StagedStep(step, ref) -> Some(step)
      case step: TaskStep =>
        // resolve the project that this step belongs to
        lazy val finalRef = step.task.scope.project.fold(identity, ref, ref)
        // put the step's task in the right project scope
        lazy val finalTask = finalRef / step.task
        lazy val refStep = step.copy(task = finalTask)

        // check if the task should be skipped
        // for this the sbt built-in `skip` task is used in the task's scope
        val (_, skipTask) = extracted.runTask(finalTask / skip, state)

        if (skipTask) {
          SkippedStep(
            refStep,
            SkippedMessage.SkipTrue(finalTask / skip),
            ref,
          ) -> None
        } else {
          StagedStep(refStep, ref) -> None
        }

      case step: InputTaskStep =>
        // resolve the project that this step belongs to
        lazy val finalRef =
          step.inputTask.scope.project.fold(identity, ref, ref)
        // put the step's task in the right project scope
        lazy val finalTask = finalRef / step.inputTask
        lazy val refStep = step.copy(inputTask = finalTask)

        // check if the task should be skipped
        // for this the sbt built-in `skip` task is used in the task's scope
        val (_, skipTask) = extracted.runTask(finalTask / skip, state)

        if (skipTask) {
          SkippedStep(
            refStep,
            SkippedMessage.SkipTrue(finalTask / skip),
            ref,
          ) -> None
        } else {
          StagedStep(refStep, ref) -> None
        }

      case step =>
        StagedStep(step, ref) -> None
    }
  }

  private def resultToMessages(
    result: StepResult,
    builders: MessageBuilders,
    extracted: Extracted,
  ): Seq[ResultMessage] = result match {
    case result: StepResult.Succeeded =>
      MessageBuilder.getMessages(builders.forSuccess)(result, extracted)
    case result: StepResult.Skipped =>
      MessageBuilder.getMessages(builders.forSkipped)(result, extracted)
    case result: StepResult.Failed =>
      MessageBuilder.getMessages(builders.forFailure)(result, extracted)
  }

  private def setStepStatus(
    scope: AttributeKey[?],
    result: StepResult,
    builders: MessageBuilders,
    state: State,
  ): State = {
    val extracted = Project.extract(state)
    val stepsResult = state.get(InternalStepsKeys.stepsResultAttr)
      .getOrElse(Map.empty)
    val stepsResultForScope = stepsResult.getOrElse(scope, Map.empty)
    val messages = resultToMessages(result, builders, extracted)
    state.put(
      InternalStepsKeys.stepsResultAttr,
      stepsResult.updated(
        scope,
        stepsResultForScope.updated(
          result.project -> result.step,
          result -> messages,
        ),
      ),
    )
  }

  private def stepsInputTask(key: InputKey[StateTransform]) = Def.inputTask {
    val verbose = cli.CLIParser(verboseFlag).parsed

    StateTransform { startState =>
      val extracted = Project.extract(startState)
      val grouping = (key / stepsGrouping).value

      val messageBuilders = MessageBuilders(
        (key / stepsMessagesForSuccess).value,
        (key / stepsMessagesForFailure).value,
        (key / stepsMessagesForSkipped).value,
      )

      val keyName = (key / name).value

      val newState = grouping match {
        case StepsGrouping.ByStep =>
          val aggregatedSteps =
            (key / InternalStepsKeys.pendingStepsByStep).value

          startState.log.info(s"Starting $keyName with the following steps:")
          startState.log.info(ASCIIUtils.pendingStepsByStepToASCIITree(
            aggregatedSteps,
            verbose,
            startState,
          ))

          if (
            !verbose && aggregatedSteps.exists {
              case (_, steps) => steps.exists(_.willBeSkipped)
            }
          ) {
            startState.log.info(
              "One or more steps will be skipped. Re-run command with `-v` option to see details.",
            )
          }

          lazy val showStep: Show[Step] = step =>
            StepsUtils.createShowStep(
              extracted,
              StepsUtils.ShowKeyFactory(
                // at this level, don't show the project name
                scopeMask = ScopeMask(
                  project = false,
                  config = true,
                  task = true,
                  extra = false,
                ),
              ),
              surround = "`" -> "`",
              includeStepName = false,
            ).show(step)

          /*
           * Loop over the aggregated steps, run the pending steps and skip others.
           * Pending steps derived from the same Step are grouped into the same list.
           * Meanwhile collect completed status and put them in the state.
           *
           * If one step fails, remaining steps are dropped and '''Left(state)''' is returned.
           * If all steps succeed '''Right(state)''' is returned.
           */
          aggregatedSteps.foldLeft[StateStatus](
            StateStatus.NoErrors(startState),
          ) {
            case (status, (ciStep, pendingSteps)) if status.continue =>
              lazy val log = status.state.log
              val stagedSteps = pendingSteps.filterNot(_.willBeSkipped)
              log.info("\n")
              if (stagedSteps.isEmpty) {
                log.info(
                  s"No projects have staged steps for ${ciStep.stepType} ${showStep show ciStep}",
                )
              } else {
                val projectsStr = stagedSteps.size match {
                  case 1 => "1 project"
                  case size => s"$size projects"
                }
                log.info(
                  s"Running ${ciStep.stepType} ${showStep show ciStep} for $projectsStr...",
                )
              }

              // run step for each configured project
              val action = StepsUtils.multiProjectStepToAction(
                ciStep,
                pendingSteps,
                verbose,
              )
              action(status) match {
                case MultiStepResults(status, results) =>
                  status.withState(
                    results.foldLeft(status.state) {
                      case (state, result) =>
                        setStepStatus(key.key, result, messageBuilders, state)
                    },
                  )
              }
            case (abort, _) =>
              // reached abort state, so drop remaining steps
              abort
          } match {
            case StateStatus.NoErrors(state) =>
              state
            case StateStatus.ContinuedWithErrors(state) =>
              state.fail
            case StateStatus.AbortedWithErrors(state) =>
              state.fail
          }
        case StepsGrouping.ByProject =>
          val aggregatedSteps =
            (key / InternalStepsKeys.pendingStepsByProject).value

          startState.log.info(s"Starting $keyName with the following steps:")
          startState.log.info(ASCIIUtils.pendingStepsByProjectToASCIITree(
            aggregatedSteps,
            verbose,
            startState,
          ))

          if (
            !verbose && aggregatedSteps.exists {
              case (_, steps) => steps.exists(_.willBeSkipped)
            }
          ) {
            startState.log.info(
              "One or more steps will be skipped. Re-run command with `-v` option to see details.",
            )
          }

          /* Loop over the aggregated steps, run the pending steps and skip others.
           * Pending steps are grouped by project name (outer fold) and run per project (inner fold).
           * Meanwhile collect steps status and put them in the state.
           *
           * If one step fails, remaining steps are dropped and '''Left(state)''' is returned.
           * If all steps succeed '''Right(state)''' is returned.
           */
          aggregatedSteps.foldLeft[StateStatus](
            StateStatus.NoErrors(startState),
          ) {
            case ((status), (ProjectRef(_, projectName), pendingSteps))
                if status.continue =>
              val log = status.state.log
              val stagedSteps = pendingSteps.filterNot(_.willBeSkipped)
              log.info("\n")
              if (stagedSteps.isEmpty) {
                log.info(s"No steps staged for project $projectName")
              } else {
                val stepsStr = stagedSteps.size match {
                  case 1 => "1 step"
                  case size => s"$size steps"
                }
                log.info(s"Running $stepsStr for project $projectName...")
              }

              // run the configured steps for this project
              pendingSteps.foldLeft[StateStatus](status) {
                case (status, pendingStep) if status.continue =>
                  StepsUtils.pendingStepToAction(pendingStep, verbose)(
                    status,
                  ) match {
                    case MultiStepResults(status, results) =>
                      status.withState(
                        results.foldLeft(status.state) {
                          case (state, result) =>
                            setStepStatus(
                              key.key,
                              result,
                              messageBuilders,
                              state,
                            )
                        },
                      )
                  }
                case (abort, _) =>
                  // reached abort status, so drop remaining steps
                  abort
              }
            case (abort, _) =>
              // reached abort status, so drop remaining steps
              abort
          } match {
            case StateStatus.NoErrors(state) =>
              state
            case StateStatus.ContinuedWithErrors(state) =>
              state.fail
            case StateStatus.AbortedWithErrors(state) =>
              state.fail
          }
      }

      // report status to html file
      reportStatus(
        key,
        verbose,
        Output.File(
          (LocalRootProject / target).value / (key / stepsStatusReportFileName).value,
        ),
        newState,
      )

      newState
    }
  }
}
