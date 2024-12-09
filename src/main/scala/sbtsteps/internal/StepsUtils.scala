package sbtsteps
package internal

import sbt.*
import sbt.Keys.*
import sbt.internal.*
import sbt.Scope.GlobalScope
import sbt.complete.Parser
import sbt.internal.ProjectNavigation

import model.*

/** @define actionReturn
  *   A curried function that first accepts a list of steps for which the task
  *   should be run. The resulting function accepts a start state and returns
  *   the result containing a new state and the status for each completed step.
  *   If a step is not completed due to failure, the step will not be included
  *   in the result. Instead '''succeeded''' is set to false to indicate
  *   failure.
  */
object StepsUtils {

  /** @return
    *   The skip reason if this step is a skipped step, otherwise '''None'''.
    */
  def getSkipReason(pendingStep: PendingStep): Option[SkippedMessage] =
    pendingStep match {
      case step: SkippedStep => Some(step.skipReason)
      case _ => None
    }

  /** @return
    *   The given state changed to the given project.
    */
  def setProject(projectRef: ProjectRef): State => State = { state: State =>
    if (state.currentRef != projectRef) {
      new ProjectNavigation(state)
        .setProject(projectRef.build, projectRef.project)
    } else {
      state
    }
  }

  /** @return
    *   Convenience function for a [[sbt.Show]] for [[sbt.ScopedKey]] with
    *   defaults.
    */
  def defaultShowKey(extracted: Extracted): Show[ScopedKey[?]] =
    ShowKeyFactory().create(extracted)

  /** Factory to create a [[sbt.Show]] instance for [[sbt.ScopedKey]]. Can be
    * used as template to change parameters before creation.
    *
    * @param surround
    *   Strings to surround the eventual key with (e.g. '''"<code>" ->
    *   "</code>"'''). Defaults to empty.
    * @param scopeMask
    *   Mask to configure what to print. See [[sbt.ScopeMask]]. By default all
    *   scopes are printed.
    * @param thisScope
    *   The scope replace any This instances with. Defaults to
    *   '''LocalRootProject, Zero, Zero, Zero'''. This is to prevent the project
    *   scope from being printed as '''Global'''.
    * @param includeRootProjectName
    *   If true, shows the root project scope, if false it shows only non-root
    *   project scopes.
    *
    * @note
    *   Generally do not use '''surround''' for terminal colors, because these
    *   are globally set or unset depending on the environment. In that case use
    *   '''Project.showContextKey'''.
    */
  case class ShowKeyFactory(
    surround: Surround = Surround.empty,
    scopeMask: ScopeMask = ScopeMask(),
    thisScope: Scope = Global.copy(project = Select(LocalRootProject)),
    includeRootProjectName: Boolean = false,
  ) {

    final val prefix = surround.prefix
    final val postfix = surround.postfix

    final def create(extracted: Extracted): Show[ScopedKey[?]] =
      unresolvedKey => {
        val key = StepsUtils.resolve(unresolvedKey, extracted, thisScope)
        val keyStr = Scope.displayMasked(
          key.scope,
          key.key.label,
          {
            // show all non-root projects by default
            // show root project only if includeRootProjectName is true
            case ProjectRef(build, projectName)
                if includeRootProjectName || extracted
                  .rootProject(build) != projectName =>
              s"${projectName} /"
            case _ =>
              ""
          },
          scopeMask,
          showZeroConfig = false,
        )
        surround(keyStr)
      }
  }

  /** Factory to create a [[sbt.Show]] instance for [[sbt.Exec]]. Can be used as
    * template to change parameters before creation.
    *
    * @param surround
    *   Strings to surround the eventual key with (e.g. '''"<code>",
    *   "</code>"'''). Defaults to empty.
    * @param projectRef
    *   Optional project that this command applies to.
    * @param includeRootProject
    *   If true, includes the name in root project. If false, includes only the
    *   names in non-root projects, but omits the name in the root project.
    *   Defaults to false.
    */
  case class ShowExecFactory(
    surround: Surround = Surround.empty,
    projectRef: Option[ProjectRef] = None,
    includeRootProjectName: Boolean = false,
  ) {

    def create(extracted: Extracted): Show[Exec] =
      exec => {
        val projectCmd = projectRef.collect {
          case ProjectRef(build, projectName) if
                includeRootProjectName || extracted.rootProject(
                  build,
                ) != projectName =>
            // add project command to make it easy to paste into sbt shell
            s"project $projectName; "
        }.getOrElse("")
        surround(s"$projectCmd${exec.commandLine}")
      }
  }

  /** Create an [[sbt.Show]] instance for [[PendingStep]] using the given show
    * instances.
    *
    * @param extracted
    *   Needed to relate the key or command to the build definition.
    * @param showKeyFactory
    *   Pass an optional factory to [[sbt.Show]] [[sbt.ScopedKey]] or use the
    *   default.
    * @param showExecFactory
    *   Pass an optional factory to [[sbt.Show]] [[sbt.Exec]] or use the
    *   default.
    * @param includeStepName
    *   If true and step name is defined, the name is appended. Defaults to
    *   false.
    * @param includeRootProject
    *   If true, includes the name in root project. If false, includes only the
    *   names in non-root projects, but omits the name in the root project.
    *   Defaults to false.
    *
    * @note
    *   The '''includeRootProjectName''' argument overwrites both factories, so
    *   no use setting them there!
    */
  def createShowPendingStep(
    extracted: Extracted,
    showKeyFactory: ShowKeyFactory = ShowKeyFactory(),
    showExecFactory: ShowExecFactory = ShowExecFactory(),
    surround: Surround = Surround.empty,
    includeStepName: Boolean = false,
    includeRootProjectName: Boolean = false,
  ): Show[PendingStep] =
    pendingStep => {
      createShowStep(
        extracted,
        showKeyFactory.copy(
          includeRootProjectName = includeRootProjectName,
        ),
        showExecFactory.copy(
          includeRootProjectName = includeRootProjectName,
          projectRef = Some(pendingStep.project),
        ),
        surround = surround,
        includeStepName = includeStepName,
      ).show(pendingStep.step)
    }

  /** Create an [[sbt.Show]] instance for [[model.Step]]
    * using the given show instances.
    *
    * @param extracted
    *   Needed to relate the key or command to the build definition.
    * @param showKeyFactory
    *   Pass an optional factory to [[sbt.Show]] [[sbt.ScopedKey]] or use the
    *   default.
    * @param showExecFactory
    *   Pass an optional factory to [[sbt.Show]] [[sbt.Exec]] or use the
    *   default.
    * @param surround
    *   Strings to surround both key and exec with (e.g. '''"<code>" ->
    *   "</code>"'''). Defaults to empty. Note that both show factories have a
    *   surround as well. If both are set, this argument will be the outer
    *   surround.
    * @param includeStepName
    *   If true and step name is defined, this step is shown as "(cmd) name".
    *   Defaults to false.
    */
  def createShowStep(
    extracted: Extracted,
    showKeyFactory: ShowKeyFactory = ShowKeyFactory(),
    showExecFactory: ShowExecFactory = ShowExecFactory(),
    surround: Surround = Surround.empty,
    includeStepName: Boolean = false,
  ): Show[Step] =
    step => {
      def withName(str: String) = step.name match {
        case Some(name) if includeStepName => s"($str) $name"
        case _ => str
      }
      // denote cross build with "+" to make it easy to paste into sbt shell
      lazy val crossPrefix = if (step.crossBuild) "+" else ""
      step match {
        case step: TaskStep =>
          val showKey = showKeyFactory
            .copy(
              surround = showKeyFactory.surround
                // put the surround in this argument list around the key surround
                .appendOutside(surround)
                // put the task input in the inner surround
                .appendInside(Surround(crossPrefix, "")),
            )
            .create(extracted)
          withName(showKey show step.task)
        case step: InputTaskStep =>
          val inputStr = if (step.input.nonEmpty) s" ${step.input}" else ""
          val showKey = showKeyFactory
            .copy(
              surround = showKeyFactory.surround
                // put the surround in this argument list around the key surround
                .appendOutside(surround)
                // put the '+' just before the key
                // put the input string just after the key
                .appendInside(Surround(crossPrefix, inputStr)),
            )
            .create(extracted)
          withName(showKey show step.inputTask)
        case step: CommandStep =>
          val showExec =
            showExecFactory.copy(
              // put the surround in this argument list around the exec surround
              surround = showExecFactory.surround.appendOutside(surround),
            ).create(extracted)
          withName(showExec show step.command)
      }
    }

  /** Resolve the given key to a given scope.
    *
    * @param key
    *   The key to resolve.
    * @param thisScope
    *   The scope to set on the key. Defaults to '''GlobalScope'''. Only change
    *   if you get an undesired result.
    * @return
    *   The key with the scope applied.
    */
  def resolve[T](
    key: ScopedKey[T],
    extracted: Extracted,
    thisScope: Scope = GlobalScope,
  ): ScopedKey[T] = {
    Project.mapScope(
      Scope.resolveScope(
        thisScope,
        extracted.currentRef.build,
        extracted.rootProject,
      ),
    )(key.scopedKey)
  }

  /** Create a runnable action from a single command, preserving remaining
    * commands.
    *
    * @return
    *   An action that returns both the new state and a Result indicating
    *   success or failure.
    *
    * @note
    *   This was adapted from
    *   [[https://github.com/sbt/sbt-release/blob/663cfd426361484228a21a1244b2e6b0f7656bdf/src/main/scala/ReleasePlugin.scala#L99-L115]]
    */
  def commandToAction(
    command: Exec,
  ): State => (State, Result[Unit]) = { startState: State =>
    // placeholder command for catching failures
    // this is not actually run as command, but discarded
    val FailureCommand = Exec(s"--failure--", None, None)

    @annotation.tailrec
    def rec(
      command: Exec,
      state: State,
    ): (State, Result[Unit]) = {
      val nextState =
        Parser.parse(command.commandLine, state.combinedParser) match {
          case Right(cmd) => cmd()
          case Left(msg) =>
            throw sys.error(s"Invalid programmatic input:\n$msg")
        }
      nextState.remainingCommands match {
        case Nil =>
          // no commands left, everything succeeded
          nextState
            .copy(
              // pop the original fields back, so that sbt continues normally
              onFailure = startState.onFailure,
              remainingCommands = startState.remainingCommands,
            ) -> Value(())
        case FailureCommand :: _ =>
          // our own onFailure is triggered, so the command failed
          nextState.copy(
            // pop the original fields back, so that sbt continues normally
            onFailure = startState.onFailure,
            remainingCommands = startState.remainingCommands,
          ) -> Inc(
            Incomplete(
              // include command as node for future reference
              node = Some(command),
              tpe = Incomplete.Error,
              // don't know how to extract the underlying error message, so a generic one will have to do
              message = Some(s"command failed"),
            ),
          )
        case head :: tail =>
          rec(head, nextState.copy(remainingCommands = tail))
      }
    }

    rec(
      command,
      startState.copy(
        // replace onFailure with something we can use later to catch a failed command
        onFailure = Some(FailureCommand),
        // stash the remaining commands to pop back later
        remainingCommands = Nil,
      ),
    )
  }

  /** Create a runnable action from a single pending step.
    *
    * @param pendingStep
    *   The step to run.
    * @param verbose
    *   Log more information while running.
    * @return
    *   An action that accepts a state and returns [[MultiStepResults]] with
    *   zero or one result. No result means that the step has not run.
    */
  def pendingStepToAction(
    pendingStep: PendingStep,
    verbose: Boolean,
  ): StateStatus => MultiStepResults = { stateStatus =>
    val extracted = Project.extract(stateStatus.state)
    val stepAction = pendingStep.step match {
      case step: TaskStep =>
        multiProjectTaskKeyToAction(
          step.task,
          verbose,
        )
      case step: InputTaskStep =>
        multiProjectInputKeyToAction(
          step.inputTask,
          step.input,
          verbose,
        )
      case step: CommandStep =>
        multiProjectCommandToAction(step.command, verbose)
    }

    if (pendingStep.crossBuild) {
      CrossUtils.multiProjectCrossBuildToAction(
        Seq(pendingStep),
        stepAction,
      )(stateStatus)
    } else {
      stepAction(Seq(pendingStep))(stateStatus)
    }
  }

  /** Create a runnable multi-project action from the given step and pending
    * steps. The step will be run for each project in the pending step. Each
    * pending step must refer to the same step passed to this method
    *
    * @param step
    *   The step to run.
    * @param pendingSteps
    *   The pending steps for each project to evaluate step again.
    * @param verbose
    *   Log more information while running.
    * @return
    */
  final def multiProjectStepToAction(
    step: Step,
    pendingSteps: Seq[PendingStep],
    verbose: Boolean,
  ): StateStatus => MultiStepResults = {
    val stepAction = step match {
      case step: TaskStep =>
        multiProjectTaskKeyToAction(step.task, verbose)
      case step: InputTaskStep =>
        multiProjectInputKeyToAction(step.inputTask, step.input, verbose)
      case step: CommandStep =>
        multiProjectCommandToAction(step.command, verbose)
    }

    if (step.crossBuild) {
      CrossUtils.multiProjectCrossBuildToAction(
        pendingSteps,
        stepAction,
      )
    } else {
      stepAction(pendingSteps)
    }
  }

  /** Create a runnable multi-project action from the given command. Before
    * running the command, the state is switched to the project in the next
    * step.
    *
    * @param command
    *   The command to run.
    * @param verbose
    *   Log more information while running.
    * @return
    *   $actionReturn
    */
  final def multiProjectCommandToAction(
    command: Exec,
    verbose: Boolean,
  ): Seq[PendingStep] => StateStatus => MultiStepResults = {
    pendingSteps => startStatus =>
      val results =
        pendingSteps.foldLeft(MultiStepResults(startStatus, Nil)) {
          case (MultiStepResults(status, results), staged: StagedStep)
              if status.continue =>
            status.state.log.info(
              ASCIIUtils.pendingStepToASCIITree(
                staged,
                verbose,
                includeRootProjectName = true,
                status.state,
              ),
            )
            // switch to the right project before executing the command
            StepsUtils
              .setProject(staged.project)
              .andThen(StepsUtils.commandToAction(command))
              .andThen {
                case (state, Value(value)) =>
                  val result =
                    StepResult.Succeeded(value, staged.step, staged.project)
                  MultiStepResults(
                    status = status.withState(state),
                    stepResults = results :+ result,
                  )
                case (state, Inc(err)) =>
                  // transformInc takes care that the right ScopedKey is found for the failed task
                  // https://github.com/sbt/sbt/blob/ee7a9aecc559b999f729d74508b7adafc204ef12/main/src/main/scala/sbt/EvaluateTask.scala#L524
                  val transformed =
                    EvaluateTask.transformInc(Inc(err)).toEither.left.get
                  // make sure the error is properly logged
                  val str = Project.extract(state).structure.streams(state)
                  EvaluateTask.logIncomplete(transformed, status.state, str)
                  val result =
                    StepResult.Failed(transformed, staged.step, staged.project)
                  MultiStepResults(
                    // continue or abort depending on the step setting (default is abort)
                    status = if (staged.alwaysContinue) {
                      StateStatus.ContinuedWithErrors(state)
                    } else {
                      StateStatus.AbortedWithErrors(state)
                    },
                    stepResults = results :+ result,
                  )
              }(status.state)

          case (MultiStepResults(status, results), skipped: SkippedStep)
              if status.continue =>
            if (verbose) {
              status.state.log.info(
                ASCIIUtils.pendingStepToASCIITree(
                  skipped,
                  verbose,
                  includeRootProjectName = true,
                  status.state,
                ),
              )
            }
            val result = StepResult.Skipped(
              skipped.skipReason,
              skipped.step,
              skipped.project,
            )
            MultiStepResults(status, results :+ result)

          case (failAndAbort, _) =>
            failAndAbort
        }

      // switch back to the project before the commands were executed
      results.copy(
        status = results.status.withState(StepsUtils.setProject(
          startStatus.state.currentRef,
        )(results.status.state)),
      )
  }

  /** Create a runnable multi-project action from an [[sbt.InputKey]].
    *
    * @param key
    *   The input key to run.
    * @param verbose
    *   Log more information while running.
    * @return
    *   $actionReturn
    */
  final def multiProjectInputKeyToAction[T](
    key: InputKey[T],
    input: String,
    verbose: Boolean,
  ): Seq[PendingStep] => StateStatus => MultiStepResults = {
    pendingSteps => status =>
      multiProjectKeyToAction[InputTask, T](
        key.scopedKey,
        inputKeyToTask(input, status.state),
        verbose,
      )(pendingSteps)(status)
  }

  /** Create a runnable multi-project action from a [[sbt.TaskKey]].
    *
    * @param key
    *   The task key to run.
    * @param toTask
    *   Function to convert the scoped key to a runnable task.
    * @param verbose
    *   Log more information while running.
    * @return
    *   $actionReturn
    */
  final def multiProjectTaskKeyToAction[T](
    key: TaskKey[T],
    verbose: Boolean,
  ): Seq[PendingStep] => StateStatus => MultiStepResults = {
    pendingSteps => status =>
      multiProjectKeyToAction[Task, T](
        key.scopedKey,
        taskKeyToTask(status.state),
        verbose,
      )(pendingSteps)(status)
  }

  /** Create a runnable multi-project action from a [[sbt.ScopedKey]] that's
    * convertible to a [[sbt.Task]].
    *
    * @param key
    *   The key to run.
    * @param toTask
    *   Function to convert the scoped key to a runnable task.
    * @param verbose
    *   Log more information while running.
    * @return
    *   $actionReturn
    */
  final def multiProjectKeyToAction[K[_], T](
    key: ScopedKey[K[T]],
    toTask: ScopedKey[K[T]] => Task[Result[T]],
    verbose: Boolean,
  ): Seq[PendingStep] => StateStatus => MultiStepResults = {
    pendingSteps => status =>
      val extracted = Project.extract(status.state)
      import extracted.*
      lazy val log = status.state.log

      // converts a pending step into a task that can be run by sbt
      // skipped steps will do nothing and give back a None
      // staged steps will actually run and give back a Some(Result)
      // the pending step is kept for later reference
      def stepToTask(
        step: PendingStep,
      ): Task[StepResult] = {

        // resolve given key to the project corresponding with the step
        val resolvedKey = resolve(
          key,
          extracted,
          thisScope = Global.copy(project = Select(step.project)),
        )

        // the Scala version for this step will be printed later (esp. useful for cross building)
        lazy val projectScalaVersion =
          extracted.getOpt((step.project) / scalaVersion)

        step match {
          case skipped: SkippedStep =>
            task {
              // only log skipped steps in verbose mode
              if (verbose) {
                log.info(
                  ASCIIUtils.pendingStepToASCIITree(
                    skipped,
                    verbose,
                    includeRootProjectName = true,
                    status.state,
                  ),
                )
              }
              StepResult.Skipped(
                skipped.skipReason,
                skipped.step,
                skipped.project,
              )
            }
          case staged: StagedStep =>
            task {
              log.info(
                ASCIIUtils.pendingStepToASCIITree(
                  staged,
                  verbose,
                  includeRootProjectName = true,
                  status.state,
                  scalaVersion = projectScalaVersion,
                ),
              )
            } && toTask(resolvedKey).map {
              case Value(value) =>
                StepResult.Succeeded(value, staged.step, staged.project)
              case Inc(err) =>
                StepResult.Failed(err, staged.step, staged.project)
            }
        }
      }

      // adapted from sbt.internal.Aggregation.timedRun
      // this will actually run the aggregated task and give a new state
      EvaluateTask.withStreams(structure, status.state) {
        str =>
          // create a single task out of all pending steps
          // use MultiStepResults to capture the result
          // note that the state in the result is not changed yet, this is done below
          val aggregatedTaskToRun = pendingSteps
            .foldLeft(task(MultiStepResults(status, Nil))) {
              case (accTask, step) =>
                accTask.flatMap {
                  case MultiStepResults(status, results) if status.continue =>
                    stepToTask(step).map {
                      case result: StepResult.Succeeded =>
                        MultiStepResults(
                          status,
                          results :+ result,
                        )
                      case result: StepResult.Skipped =>
                        MultiStepResults(
                          status,
                          results :+ result,
                        )
                      case result: StepResult.Failed =>
                        // transformInc takes care that the right ScopedKey is found for the failed task
                        // https://github.com/sbt/sbt/blob/ee7a9aecc559b999f729d74508b7adafc204ef12/main/src/main/scala/sbt/EvaluateTask.scala#L524
                        val transformed = EvaluateTask.transformInc(
                          Inc(result.error),
                        ).toEither.left.get
                        // make sure the error is properly logged
                        EvaluateTask.logIncomplete(
                          transformed,
                          status.state,
                          str,
                        )
                        MultiStepResults(
                          // continue or abort depending on the step setting
                          status = if (step.alwaysContinue) {
                            StateStatus.ContinuedWithErrors(status.state)
                          } else {
                            StateStatus.AbortedWithErrors(status.state)
                          },
                          results :+ result.copy(error = transformed),
                        )
                    }
                  case results: MultiStepResults =>
                    // reached abort state, so drop remaining steps
                    task(results)
                }
            }
          val transform = EvaluateTask.nodeView(
            status.state,
            str,
            Nil,
            sbt.std.Transform.DummyTaskMap(Nil),
          )
          val (newState, result) = EvaluateTask.runTask(
            aggregatedTaskToRun,
            status.state,
            str,
            structure.index.triggers,
            EvaluateTask.extractedTaskConfig(
              extracted,
              extracted.structure,
              status.state,
            ),
          )(transform)

          // capture the task results and transform into MultiStepResults with StepStatus
          result match {
            case Value(results) =>
              // apply the new state to the step result
              results.copy(status = results.status.withState(newState))
            case Inc(err) =>
              // the entire run has failed, so we have no step results to return
              // this only happens if an exception occurs around the handling of the task
              // so usually this indicates a bug in the plugin
              // transformInc takes care that the right ScopedKey is found for the failed task
              // https://github.com/sbt/sbt/blob/ee7a9aecc559b999f729d74508b7adafc204ef12/main/src/main/scala/sbt/EvaluateTask.scala#L524
              val transformed =
                EvaluateTask.transformInc(Inc(err)).toEither.left.get
              // make sure the error is properly logged
              EvaluateTask.logIncomplete(transformed, status.state, str)
              MultiStepResults(StateStatus.AbortedWithErrors(newState), Nil)
          }
      }
  }

  /** Convert a [[sbt.TaskKey]] to a runnable [[sbt.Task]].
    */
  def taskKeyToTask[T](
    state: State,
  )(
    key: ScopedKey[Task[T]],
  ): Task[Result[T]] = {
    val extracted = Project.extract(state)

    getScopedKey(key, state).toEither.fold(
      inc =>
        task {
          inc.message.foreach(state.log.err(_))
          Inc(inc)
        },
      _.result,
    )
  }

  /** Convert an [[sbt.InputKey]] to a runnable [[sbt.Task]] with the given input.
    * @return
    *   Runnable [[sbt.Task]] that either results in a [[sbt.Value]] or an [[sbt.Inc]].
    *   Invalid input will also result in an [[sbt.Inc]].
    */
  def inputKeyToTask[T](
    input: String,
    state: State,
  )(
    key: ScopedKey[InputTask[T]],
  ): Task[Result[T]] = {
    val extracted = Project.extract(state)

    getScopedKey(key, state).toEither.fold(
      inc =>
        task {
          inc.message.foreach(state.log.err(_))
          Inc(inc)
        },
      inputTask => {
        Parser.parse(s" $input", inputTask.parser(state)) match {
          case Right(task) =>
            task.result
          case Left(msg) =>
            task {
              val err = s"Invalid programmatic input:\n$msg"
              state.log.err(err)
              Inc(
                Incomplete(
                  node = Some(key.scopedKey),
                  tpe = Incomplete.Error,
                  message = Some(err),
                ),
              )
            }
        }
      },
    )
  }

  /** Helper function to safely get a value from a [[sbt.ScopedKey]].
    *
    * @param key
    *   The key to unpack.
    * @param state
    * @return
    *   [[sbt.Value]] if the key is found. [[sbt.Inc]] if the key is undefined.
    */
  def getScopedKey[T](
    key: ScopedKey[T],
    state: State,
  ): Result[T] = {
    val extracted = Project.extract(state)
    extracted.structure.data
      .get(key.scope, key.key)
      .map(Value(_))
      .getOrElse {
        val keyStr = extracted.showKey.show(key)
        Inc(
          Incomplete(
            node = Some(key),
            tpe = Incomplete.Error,
            message = Some(s"$keyStr is undefined"),
          ),
        )
      }
  }

}
