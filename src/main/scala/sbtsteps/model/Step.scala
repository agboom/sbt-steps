package sbtsteps
package model

import sbt.*

/** A single step to run.
  *
  * @define Tpe
  *   Step
  * @define tpe
  *   step
  * @define enableCrossBuildDoc
  *   Configure this step to run for all cross Scala versions.
  * @define crossBuildNote
  *   You can enable cross build even when '''crossScalaVersions''' is not
  *   defined in your build definition, because the default value is set to
  *   '''scalaVersion'''.
  * @define disableCrossBuildDoc
  *   Configure this step to run only for a single Scala version.
  */
sealed trait Step {

  protected type StepType <: Step

  val stepType: String

  /** Optional name for this $tpe, which will be shown during run.
    */
  val name: Option[String]

  /** If true, this step will be run for all '''crossScalaVersions'''.
    */
  val crossBuild: Boolean

  /** Whether this $tpe should be run once for the entire build or not. This is
    * useful if you have a plugin defining common steps, but want to deduplicate
    * them in the aggregate steps.
    */
  val runOnce: Boolean

  /** Which project to run this $tpe in. The default is '''ThisProject'''.
    */
  val projectFilter: ProjectReference

  /** If true, continue to the next step regardless of the result of this $tpe.
    */
  val alwaysContinue: Boolean

  /** Give this $tpe a meaningful name, which will be shown during run.
    */
  def named(name: String): StepType

  /** Configure this $tpe to run only once for the entire build. This is useful
    * if you have steps shared with multiple projects, but want to deduplicate
    * them in the project steps so that it's run only once. By default a step is
    * not deduplicated.
    */
  def once: StepType

  /** Configure this $tpe to run not once but for the entire build definition.
    * This resets a previous '''once''' invocation. This is the default for new
    * steps.
    *
    * @note
    *   This will not influence any previous '''forProject''' invocations. A
    *   project filter still applies.
    */
  def whenever: StepType

  /** Configure this $tpe to run only for a certain project. By default this
    * step is run for all projects.
    * @note
    *   To reset to the default, invoke '''forProject(ThisProject)''' on this
    *   step.
    */
  def forProject(project: ProjectReference): StepType

  /** Continue if this $tpe fails. However, failure of this $tpe will result in
    * an overall failed status.
    */
  def continueOnError: StepType

  /** Abort if this $tpe fails. Failure of this $tpe will result in an overall
    * failed status.
    */
  def abortOnError: StepType
}

/** A step that will run a task.
  *
  * @see
  *   [[TaskStep.apply]]
  * @define Tpe
  *   TaskStep
  * @define tpe
  *   task step
  */
final case class TaskStep private (
  task: TaskKey[?],
  name: Option[String],
  crossBuild: Boolean,
  runOnce: Boolean,
  projectFilter: ProjectReference,
  alwaysContinue: Boolean,
) extends Step {

  final type StepType = TaskStep

  final lazy val stepType: String = "task"

  final def named(name: String): TaskStep = copy(name = Some(name))

  /** $enableCrossBuildDoc
    * @note
    *   $crossBuildNote
    */
  final def enableCrossBuild: TaskStep = copy(crossBuild = true)

  /** $disableCrossBuildDoc
    * @note
    *   $crossBuildNote
    */
  final def disableCrossBuild: TaskStep = copy(crossBuild = false)

  /** $enableCrossBuildDoc
    * @note
    *   $crossBuildNote
    */
  final def `unary_+`: TaskStep = enableCrossBuild

  final def once: TaskStep = copy(runOnce = true)

  final def whenever: TaskStep = copy(runOnce = false)

  final def forProject(project: ProjectReference): TaskStep =
    copy(projectFilter = project)

  final def continueOnError: TaskStep = copy(alwaysContinue = true)

  final def abortOnError: TaskStep = copy(alwaysContinue = false)

  /* Make default copy private to mitigate breaking binary compatibility */
  final private[sbtsteps] def copy(
    task: TaskKey[?] = this.task,
    name: Option[String] = this.name,
    crossBuild: Boolean = this.crossBuild,
    runOnce: Boolean = this.runOnce,
    projectFilter: ProjectReference = this.projectFilter,
    alwaysContinue: Boolean = this.alwaysContinue,
  ): TaskStep = TaskStep(
    task,
    name,
    crossBuild,
    runOnce,
    projectFilter,
    alwaysContinue,
  )
}

object TaskStep {

  /** A step that will run a task.
    *
    * @param task
    *   The task to run for this step, e.g. '''Compile / compile'''.
    * @note
    *   Task steps do not follow project aggregation. If you want a task to run
    *   for the multiple projects define the task step for each project, for
    *   example with a shared setting.
    */
  final def apply(task: TaskKey[?]): TaskStep = new TaskStep(
    task,
    name = None,
    crossBuild = false,
    runOnce = false,
    projectFilter = ThisProject,
    alwaysContinue = false,
  )

  final def unapply(step: TaskStep): Option[TaskKey[?]] = Some(step.task)

  /* Make default apply private to mitigate breaking binary compatibility */
  final private def apply(
    task: TaskKey[_],
    name: Option[String],
    crossBuild: Boolean,
    runOnce: Boolean,
    projectFilter: ProjectReference,
    alwaysContinue: Boolean,
  ): TaskStep =
    new TaskStep(task, name, crossBuild, runOnce, projectFilter, alwaysContinue)
}

/** A step that will run an input task.
  *
  * @see
  *   [[InputTaskStep.apply]]
  * @define Tpe
  *   InputTaskStep
  * @define tpe
  *   input task step
  */
final case class InputTaskStep private (
  inputTask: InputKey[?],
  input: String,
  name: Option[String],
  crossBuild: Boolean,
  runOnce: Boolean,
  projectFilter: ProjectReference,
  alwaysContinue: Boolean,
) extends Step {

  final type StepType = InputTaskStep

  final lazy val stepType: String = "input task"

  /** Set the input to apply to this input task when the step is run.
    */
  final def withInput(input: String): InputTaskStep = copy(input = input)

  final def named(name: String): InputTaskStep = copy(name = Some(name))

  /** $enableCrossBuildDoc
    * @note
    *   $crossBuildNote
    */
  final def enableCrossBuild: InputTaskStep = copy(crossBuild = true)

  /** $disableCrossBuildDoc
    * @note
    *   $crossBuildNote
    */
  final def disableCrossBuild: InputTaskStep = copy(crossBuild = false)

  /** $enableCrossBuildDoc
    * @note
    *   $crossBuildNote
    */
  final def `unary_+`: InputTaskStep = enableCrossBuild

  final def once: InputTaskStep = copy(runOnce = true)

  final def whenever: InputTaskStep = copy(runOnce = false)

  final def forProject(project: ProjectReference): InputTaskStep =
    copy(projectFilter = project)

  final def continueOnError: InputTaskStep = copy(alwaysContinue = true)

  final def abortOnError: InputTaskStep = copy(alwaysContinue = false)

  /* Make default copy private to mitigate breaking binary compatibility */
  final private[sbtsteps] def copy(
    inputTask: InputKey[?] = this.inputTask,
    input: String = this.input,
    name: Option[String] = this.name,
    crossBuild: Boolean = this.crossBuild,
    runOnce: Boolean = this.runOnce,
    projectFilter: ProjectReference = this.projectFilter,
    alwaysContinue: Boolean = this.alwaysContinue,
  ): InputTaskStep = new InputTaskStep(
    inputTask,
    input,
    name,
    crossBuild,
    runOnce,
    projectFilter,
    alwaysContinue,
  )
}

object InputTaskStep {

  /** A step that will run an input task.
    *
    * @param inputTask
    *   The input task to run for this step, e.g. '''Test / testOnly'''.
    * @note
    *   Task steps do not follow project aggregation. If you want a task to run
    *   for the multiple projects define the task step for each project, for
    *   example with a shared setting.
    * @note
    *   Input tasks usually need input. Use '''withInput''' to set the input for
    *   this step.
    */
  final def apply(inputTask: InputKey[?]): InputTaskStep = InputTaskStep(
    inputTask,
    input = "",
    name = None,
    crossBuild = false,
    runOnce = false,
    projectFilter = ThisProject,
    alwaysContinue = false,
  )

  final def unapply(step: InputTaskStep): Option[InputKey[?]] =
    Some(step.inputTask)

  /* Make default apply private to mitigate breaking binary compatibility */
  final private def apply(
    inputTask: InputKey[?],
    input: String,
    name: Option[String],
    crossBuild: Boolean,
    runOnce: Boolean,
    projectFilter: ProjectReference,
    alwaysContinue: Boolean,
  ): InputTaskStep = new InputTaskStep(
    inputTask,
    input,
    name,
    crossBuild,
    runOnce,
    projectFilter,
    alwaysContinue,
  )
}

/** A step that will run a command.
  *
  * @see
  *   [[CommandStep.apply]]
  * @define Tpe
  *   CommandStep
  * @define tpe
  *   command step
  */
case class CommandStep private (
  command: Exec,
  name: Option[String],
  runOnce: Boolean,
  projectFilter: ProjectReference,
  alwaysContinue: Boolean,
) extends Step {

  final type StepType = CommandStep

  final lazy val stepType: String = "command"

  final lazy val crossBuild: Boolean = false

  final def named(name: String): CommandStep = copy(name = Some(name))

  final def once: CommandStep = copy(runOnce = true)

  final def whenever: CommandStep = copy(runOnce = false)

  final def forProject(project: ProjectReference): CommandStep =
    copy(projectFilter = project)

  final def continueOnError: CommandStep = copy(alwaysContinue = true)

  final def abortOnError: CommandStep = copy(alwaysContinue = false)

  /* Make default copy private to mitigate breaking binary compatibility */
  final private[sbtsteps] def copy(
    command: Exec = this.command,
    name: Option[String] = this.name,
    runOnce: Boolean = this.runOnce,
    projectFilter: ProjectReference = this.projectFilter,
    alwaysContinue: Boolean = this.alwaysContinue,
  ): CommandStep = new CommandStep(
    command,
    name,
    runOnce,
    projectFilter,
    alwaysContinue,
  )
}

object CommandStep {

  /** A step that will run a command.
    *
    * @param command
    *   The command to run for this step, e.g. '''"scripted"'''
    * @note
    *   Command steps will run as-is in the project scope that the step is
    *   configured in. If a command contains a task, it will be run for the
    *   aggregated projects as well. This is identical to running the command
    *   from the sbt shell. It's recommended to use '''forProject''' to limit
    *   the number of projects this command is run in.
    */
  final def apply(command: String): CommandStep = new CommandStep(
    Exec(command, None),
    name = None,
    runOnce = false,
    projectFilter = ThisProject,
    alwaysContinue = false,
  )

  final def unapply(step: CommandStep): Option[String] =
    Some(step.command.commandLine)

  /* Make default apply private to mitigate breaking binary compatibility */
  final private def apply(
    name: Option[String],
    command: Exec,
    runOnce: Boolean,
    projectFilter: ProjectReference,
    alwaysContinue: Boolean,
  ): CommandStep =
    new CommandStep(command, name, runOnce, projectFilter, alwaysContinue)
}
