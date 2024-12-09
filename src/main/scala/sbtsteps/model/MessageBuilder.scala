package sbtsteps
package model

import sbt.*
import scala.reflect.ClassTag

/** Function that creates a list of [[ResultMessage]]s from a [[StepResult]].
  * Every builder has a label to make it easier to distinguish in the sbt shell
  * (with '''show''' and '''inspect''').
  */
sealed trait MessageBuilder[-R <: StepResult, +M <: ResultMessage]
    extends ((R, Extracted) => Seq[M]) {
  def apply(result: R, extracted: Extracted): Seq[M]

  def label: String

  override def toString: String = label
}

/** @define nameDoc
  *   A distinguishable name for the builder. Usually the task name or command
  *   name.
  * @define fnDoc
  *   A partial function that creates the message(s) from the step result. If
  *   the partial function is not defined for the input, no messages are
  *   generated.
  */
object MessageBuilder {

  /** Get all messages by passing the given result to the given builders.
    */
  def getMessages[R <: StepResult, M <: ResultMessage](
    builders: Seq[MessageBuilder[R, M]],
  )(
    result: R,
    extracted: Extracted,
  ): Seq[ResultMessage] =
    builders.foldLeft(Seq.empty[ResultMessage]) {
      case (msgs, builder) =>
        msgs ++ builder(result, extracted)
    }

  /** Generate messages from a completed step.
    *
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def apply[R <: StepResult, M <: ResultMessage](
    name: String,
  )(
    fn: PartialFunction[(StepResult, Extracted), Seq[M]],
  ): MessageBuilder[R, M] = new MessageBuilder[R, M] {
    def label: String = name
    def apply(result: R, extracted: Extracted): Seq[M] =
      PartialFunction.condOpt((result, extracted))(fn).toSeq.flatten
  }

  /** Generate messages from a succeeded step.
    *
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSuccess[T: ClassTag, M <: ResultMessage](name: String)(
    fn: PartialFunction[(T, Step, ProjectRef, Extracted), Seq[M]],
  ): MessageBuilder[StepResult.Succeeded, M] = MessageBuilder(name) {
    case (StepResult.Succeeded(value: T, step, ref), extracted) =>
      PartialFunction.condOpt((value, step, ref, extracted))(fn).toSeq.flatten
  }

  /** Generate a single message from a succeeded step.
    *
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSuccessSingle[T: ClassTag, M <: ResultMessage](name: String)(
    fn: PartialFunction[(T, Step, ProjectRef, Extracted), M],
  ): MessageBuilder[StepResult.Succeeded, M] =
    forSuccess(name)(fn.andThen(_ :: Nil))

  /** Generate messages from a failed step.
    *
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forFailure[M <: ResultMessage](name: String)(
    fn: PartialFunction[(Incomplete, Step, ProjectRef, Extracted), Seq[M]],
  ): MessageBuilder[StepResult.Failed, M] = MessageBuilder(name) {
    case (StepResult.Failed(error, step, ref), extracted) =>
      PartialFunction.condOpt((error, step, ref, extracted))(fn).toSeq.flatten
  }

  /** Generate a single message from a failed step.
    *
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forFailureSingle[M <: ResultMessage](name: String)(
    fn: PartialFunction[(Incomplete, Step, ProjectRef, Extracted), M],
  ): MessageBuilder[StepResult.Failed, M] =
    forFailure(name)(fn.andThen(_ :: Nil))

  /** Default messages for any failed step. Converts the [[sbt.Incomplete]]
    * result into failure messages.
    */
  def forFailureDefault: MessageBuilder[StepResult.Failed, ResultMessage] =
    forFailure("failure-default") {
      case (error, _, _, _) =>
        FailureMessage.fromIncomplete(error)
    }

  /** Generate skipped messages from a skipped step.
    *
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSkipped[M <: ResultMessage](name: String)(
    fn: PartialFunction[(SkippedMessage, Step, ProjectRef, Extracted), Seq[M]],
  ): MessageBuilder[StepResult.Skipped, M] =
    MessageBuilder(name) {
      case (StepResult.Skipped(reason, step, ref), extracted) =>
        PartialFunction.condOpt((reason, step, ref, extracted))(fn).toSeq.flatten
    }

  /** Generate a single messages from a skipped step.
    *
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSkippedSingle[M <: ResultMessage](name: String)(
    fn: PartialFunction[(SkippedMessage, Step, ProjectRef, Extracted), M],
  ): MessageBuilder[StepResult.Skipped, M] =
    forSkipped(name)(fn.andThen(_ :: Nil))

  /** Default messages for any skipped step. The skip reason from the result is
    * returned unmodified.
    */
  def forSkippedDefault: MessageBuilder[StepResult.Skipped, ResultMessage] =
    forSkipped("skipped-default") {
      case (reason, _, _, _) => reason :: Nil
    }
}

/** @define keyDoc
  *   The task key from the completed step.
  * @define nameDoc
  *   A distinguishable name for this builder. Defaults to the command name.
  * @define fnDoc
  *   A partial function that creates the message(s) from the task step result.
  *   If the partial function is not defined for the input, no message is
  *   generated.
  */
object TaskMessageBuilder {

  /** Generate multiple messages from a succeeded task step.
    *
    * @param key
    *   $keyDoc
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSuccess[T: ClassTag, K[_], M <: ResultMessage](
    key: Def.KeyedInitialize[K[T]],
    name: Option[String] = None,
  )(
    fn: PartialFunction[(T, ProjectRef, Extracted), Seq[M]],
  ): MessageBuilder[StepResult.Succeeded, M] =
    MessageBuilder.forSuccess[T, M](name.getOrElse(key.scopedKey.key.label)) {
      case (value: T, step: TaskStep, ref, extracted)
          if step.task.key == key.scopedKey.key =>
        PartialFunction.condOpt((value, ref, extracted))(fn).toSeq.flatten
      case (value: T, step: InputTaskStep, ref, extracted)
          if step.inputTask.key == key.scopedKey.key =>
        PartialFunction.condOpt((value, ref, extracted))(fn).toSeq.flatten
    }

  /** Generate a single message from a succeeded task step.
    *
    * @param key
    *   $keyDoc
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSuccessSingle[T: ClassTag, K[_], M <: ResultMessage](
    key: Def.KeyedInitialize[K[T]],
    name: Option[String] = None,
  )(
    fn: PartialFunction[(T, ProjectRef, Extracted), M],
  ): MessageBuilder[StepResult.Succeeded, M] =
    forSuccess(key, name)(fn.andThen(_ :: Nil))

  def forFailure[M <: ResultMessage](
    key: Def.KeyedInitialize[?],
    name: Option[String] = None,
  )(
    fn: PartialFunction[(ScopedKey[?], ProjectRef, Extracted), Seq[M]],
  ): MessageBuilder[StepResult.Failed, M] =
    MessageBuilder.forFailure(name.getOrElse(key.scopedKey.key.label)) {
      case (
            Incomplete(Some(scopedKey: ScopedKey[?]), _, _, _, _),
            _,
            ref,
            extracted,
          )
          if scopedKey.key == key.scopedKey.key =>
        PartialFunction.condOpt((scopedKey, ref, extracted))(fn).toSeq.flatten
    }

  /** Generate a single message from a failed task step.
    *
    * @param key
    *   $keyDoc
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forFailureSingle[M <: ResultMessage](
    key: Def.KeyedInitialize[?],
    name: Option[String] = None,
  )(
    fn: PartialFunction[(ScopedKey[?], ProjectRef, Extracted), M],
  ): MessageBuilder[StepResult.Failed, M] =
    forFailure(key, name)(fn.andThen(_ :: Nil))

  /** Generate skipped messages from a skipped task step.
    *
    * @param key
    *   $keyDoc
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSkipped[T: ClassTag, K[M], M <: ResultMessage](
    key: Def.KeyedInitialize[K[T]],
    name: Option[String] = None,
  )(
    fn: PartialFunction[(ProjectRef, Extracted), Seq[M]],
  ): MessageBuilder[StepResult.Skipped, M] =
    MessageBuilder.forSkipped(name.getOrElse(key.scopedKey.key.label)) {
      case (_, step: TaskStep, ref, extracted)
          if step.task.key == key.scopedKey.key =>
        PartialFunction.condOpt((ref, extracted))(fn).toSeq.flatten
      case (_, step: InputTaskStep, ref, extracted)
          if step.inputTask.key == key.scopedKey.key =>
        PartialFunction.condOpt((ref, extracted))(fn).toSeq.flatten
    }

  /** Generate a single messages from a skipped task step.
    *
    * @param key
    *   $keyDoc
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSkippedSingle[T: ClassTag, K[M], M <: ResultMessage](
    key: Def.KeyedInitialize[K[T]],
    name: Option[String] = None,
  )(
    fn: PartialFunction[(ProjectRef, Extracted), M],
  ): MessageBuilder[StepResult.Skipped, M] =
    forSkipped(key, name)(fn.andThen(_ :: Nil))
}

/** @define commandDoc
  *   The command from the completed step.
  * @define nameDoc
  *   A distinguishable name for this builder. Defaults to the command name.
  * @define fnDoc
  *   A partial function that creates the message(s) from the command step
  *   result. If the partial function is not defined for the input, no message
  *   is generated.
  */
object CommandMessageBuilder {

  /** Generate multiple messages from a succeeded command step.
    *
    * @param command
    *   $commandDoc
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSuccess[M <: ResultMessage](
    command: String,
    name: Option[String],
  )(
    fn: PartialFunction[(ProjectRef, Extracted), Seq[M]],
  ): MessageBuilder[StepResult.Succeeded, M] =
    MessageBuilder(name.getOrElse(command)) {
      case (StepResult.Succeeded(_, step: CommandStep, ref), extracted)
          if step.command.commandLine == command =>
        PartialFunction.condOpt((ref, extracted))(fn).toSeq.flatten
    }

  /** Generate a single message from a succeeded command step.
    *
    * @param command
    *   $commandDoc
    * @param name
    *   $nameDoc
    * @param fn
    *   $fnDoc
    */
  def forSuccessSingle[M <: ResultMessage](
    command: String,
    name: Option[String] = None,
  )(
    fn: PartialFunction[(ProjectRef, Extracted), M],
  ): MessageBuilder[StepResult.Succeeded, M] =
    forSuccess(command, name)(fn.andThen(_ :: Nil))
}
