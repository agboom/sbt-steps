package sbtsteps
package model

import sbt.{internal as _, *}
import internal.StepsUtils

/** Message that will be shown with the step status both in the CI report and CI
  * tree. Messages are generated using [[MessageBuilder]]s.
  * @define tpe
  *   result message
  */
sealed trait ResultMessage {

  /** Create a showable string from this $tpe.
    * @see
    *   [[MessageParams]]
    */
  val show: Show[MessageParams]

  /** Create a showable string from this $tpe.
    */
  final def show(
    extracted: Extracted,
    project: ProjectRef,
    surround: Surround,
  ): String =
    show show MessageParams(extracted, project, surround)

  /** Create a showable string from this $tpe.
    */
  final def show(extracted: Extracted, project: ProjectRef): String =
    show show MessageParams(extracted, project)
}

/** A message that's shown with a succeeded step.
  * @define tpe
  *   success message
  */
sealed abstract class SuccessMessage(val show: Show[MessageParams])
    extends ResultMessage

/** Extend this trait for custom success messages, for example in a custom CI
  * plugin.
  * @example
  *   {{{
  * case class DeploySuccessMessage(name: String) extends CustomSuccessMessage({
  *     params => s"Successfully deployed \${params.surround(name)}"
  * })
  *   }}}
  * @note
  *   A message is only created after a completed step if you add it to your
  *   settings via a [[MessageBuilder]]:
  *   {{{
  * Global / stepsMessagesForSuccess += TaskMessageBuilder.forSuccessSingle(deploy) {
  *   case (_, project, extracted) => DeploySuccessMessage(extracted.get(project / name))
  * }
  *   }}}
  * @see
  *   [[TaskMessageBuilder.forSuccess]]
  * @see
  *   [[CommandMessageBuilder.forSuccess]]
  */
class CustomSuccessMessage(show: Show[MessageParams])
    extends SuccessMessage(show)

object CustomSuccessMessage {

  /** Shorthand for creating a custom success message with [[MessageParams]].
    * @example
    *   {{{
    * Global / ci / stepsMessagesForSuccess += TaskMessageBuilder.forSuccessSingle(Test / test) {
    *   _ => CustomSuccessMessage(params => s"All tests \${params.surround("passed")}!")
    * }
    *   }}}
    * @see
    *   [[TaskMessageBuilder.forSuccess]]
    * @see
    *   [[CommandMessageBuilder.forSuccess]]
    */
  def apply(show: Show[MessageParams]): CustomSuccessMessage =
    new CustomSuccessMessage(show)

  /** Shorthand for creating a custom success message without [[MessageParams]].
    * @example
    *   {{{
    * Global / ci / stepsMessagesForSuccess += TaskMessageBuilder.forSuccessSingle(Test / test) {
    *   _ => CustomSuccessMessage("All tests passed!")
    * }
    *   }}}
    * @see
    *   [[TaskMessageBuilder.forSuccess]]
    * @see
    *   [[CommandMessageBuilder.forSuccess]]
    */
  def apply(string: String): CustomSuccessMessage =
    CustomSuccessMessage(_ => string)
}

object SuccessMessage {

  /** Message to show after a successful '''publish'''.
    */
  final case class Published(artifactName: String, artifactVersion: String)
      extends SuccessMessage({
        case MessageParams(_, _, surround) =>
          s"Successfully published $artifactName ${surround(artifactVersion)}"
      })
}

/** A message that's shown with a failed step.
  * @define tpe
  *   failure message
  */
sealed trait FailureMessage extends ResultMessage {
  type Node

  def node: Node

  def showMessage: Show[MessageParams]

  protected def showNode: Show[MessageParams]

  val show: Show[MessageParams] = { params =>
    val nodeStr = showNode show params
    val messageStr = showMessage show params
    s"($nodeStr) $messageStr"
  }
}

sealed trait TaskFailureMessage extends FailureMessage {
  type Node = ScopedKey[?]

  protected val showNode: Show[MessageParams] = params =>
    StepsUtils.ShowKeyFactory(surround = params.surround).create(
      params.extracted,
    ).show(node)
}

sealed trait CommandFailureMessage extends FailureMessage {
  type Node = Exec

  protected val showNode: Show[MessageParams] = params =>
    StepsUtils.ShowExecFactory(surround = params.surround).create(
      params.extracted,
    ).show(node)
}

object CustomFailureMessage {
  class Task(val node: ScopedKey[?])(val showMessage: Show[MessageParams])
      extends TaskFailureMessage

  object Task {

    /** Custom failure message for a task step.
      */
    def apply(node: ScopedKey[?])(showMessage: Show[MessageParams]): Task =
      new Task(node)(showMessage)

    /** Custom failure message for a task step.
      */
    def apply(node: ScopedKey[?], message: String): Task =
      new Task(node)(_ => message)
  }

  /** Custom failure message for a command step.
    */
  class Command(val node: Exec)(val showMessage: Show[MessageParams])
      extends CommandFailureMessage

  object Command {

    /** Custom failure message for a command step.
      */
    def apply(node: Exec, message: String): Command =
      new Command(node)(_ => message)
  }
}

object FailureMessage {

  /** Failure message for a task step.
    */
  final case class Task(node: ScopedKey[?])(
    val showMessage: Show[MessageParams],
  ) extends TaskFailureMessage

  object Task {

    /** Failure message for a task step.
      */
    def apply(node: ScopedKey[?], message: String): TaskFailureMessage =
      Task(node)(_ => message)
  }

  /** Failure message for a command step.
    */
  final case class Command(node: Exec)(val showMessage: Show[MessageParams])
      extends CommandFailureMessage

  object Command {

    /** Failure message for a command step.
      */
    def apply(node: Exec, message: String): CommandFailureMessage =
      Command(node)(_ => message)
  }

  /** Create [[FailureMessage]]s from an [[sbt.Incomplete]]. Visits all causes of
    * the given error and creates a [[FailureMessage]] for every error that has
    * either a message or a cause defined.
    */
  final def fromIncomplete(error: Incomplete): Seq[FailureMessage] = {
    // flatten errors to a list
    (Incomplete linearize error)
      .flatMap {
        // only use errors for which there are messages to be shown
        case Incomplete(node, _, msg, _, cause)
            if msg.isDefined || cause.isDefined =>
          lazy val message =
            (msg ++ cause.map(ErrorHandling.reducedToString)).mkString("\n")
          // node in Incomplete is an `AnyRef` so we need to match on type
          node match {
            case Some(key: ScopedKey[?]) =>
              Some(FailureMessage.Task(key, message))
            case Some(key: Exec) =>
              Some(FailureMessage.Command(key, message))
            case _ =>
              None
          }

        case _ =>
          None
      }
  }
}

/** A message that's shown with a skipped step.
  * @define tpe
  *   skipped message
  */
sealed abstract class SkippedMessage(val show: Show[MessageParams])
    extends ResultMessage

/** Extend this trait for custom skipped messages, for example in a custom CI
  * plugin.
  * @example
  *   {{{
  * case class DeploySkippedMessage(name: String) extends CustomSuccessMessage({
  *     case MessageParams(_, _, surround) =>
  *       s"Skipped deploy for \${surround(name)}"
  * })
  *   }}}
  * @example
  * @note
  *   A message is only created after a completed step if you add it to your
  *   settings via a [[MessageBuilder]]:
  *   {{{
  * Global / stepsMessagesForSkipped += TaskMessageBuilder.forSkippedSingle(deploy) {
  *   case (_, project, extracted) => DeploySkippedMessage(extracted.get(project / name))
  * }
  *   }}}
  * @see
  *   [[TaskMessageBuilder.forSkipped]]
  */
class CustomSkippedMessage(show: Show[MessageParams])
    extends SkippedMessage(show)

object CustomSkippedMessage {

  /** Shorthand for creating a custom skipped message with [[MessageParams]].
    * @example
    *   {{{
    * Global / stepsMessagesForSkipped += TaskMessageBuilder.forSkippedSingle(Test / test) {
    *   _ => CustomSkippedMessage(params => s"All tests \${params.surround("skipped")}!")
    * }
    *   }}}
    * @see
    *   [[TaskMessageBuilder.forSkipped]]
    */
  def apply(show: Show[MessageParams]): CustomSkippedMessage =
    new CustomSkippedMessage(show)

  /** Shorthand for creating a custom skipped message without [[MessageParams]].
    * @example
    *   {{{
    * Global / stepsMessagesForSkipped += TaskMessageBuilder.forSkippedSingle(Test / test) {
    *   _ => CustomSkippedMessage("All tests skipped!")
    * }
    *   }}}
    * @see
    *   [[TaskMessageBuilder.forSkipped]]
    */
  def apply(string: String): CustomSkippedMessage =
    new CustomSkippedMessage(_ => string)
}

object SkippedMessage {
  final case object RunOnce extends SkippedMessage({ _ =>
        "Step is configured to run only once"
      })

  final case class ProjectFilter(
    projectFilter: ProjectReference,
    thisProject: ProjectRef,
  ) extends SkippedMessage({
        case MessageParams(_, _, surround) =>
          s"Step is configured to run only in ${surround(projectFilter)}, which ${surround(thisProject.project)} does not match",
      })

  final case class SkipTrue(skipKey: TaskKey[Boolean]) extends SkippedMessage({
        case MessageParams(extracted, _, surround) =>
          val skipStr = StepsUtils.ShowKeyFactory(surround = surround)
            .create(extracted)
            .show(skipKey)
          s"$skipStr is set to true"
      })
}

/** Parameters for showing a [[ResultMessage]].
  */
final class MessageParams private (
  _extracted: Extracted,
  _project: ProjectRef,
  _surround: Surround,
) {

  /** The current extracted build. Useful to get settings from, to show extra
    * information.
    */
  final lazy val extracted: Extracted = _extracted

  /** The project that the result applies to.
    */
  final lazy val project: ProjectRef = _project

  /** String to put around any token to make the message more readable. For
    * example: '''AnsiColor.RED''' or '''"<code>" -> "</code>"'''. Defaults to
    * '''"" -> ""'''.
    */
  final lazy val surround: Surround = _surround

  /** Prefix shorthand derived from surround.
    */
  final lazy val prefix: String = surround.prefix

  /** Postfix shorthand derived from surround.
    */
  final lazy val postfix: String = surround.postfix
}

object MessageParams {

  /** Parameters for showing a [[ResultMessage]].
    *
    * @param extracted
    *   The current extracted build. Useful to get settings from, to show extra
    *   information.
    * @param project
    *   The project that the result applies to.
    * @param surround
    *   String to put around any token to make the message more readable. For
    *   example: '''AnsiColor.RED''' or '''"<code>" -> "</code>"'''. Defaults to
    *   '''"" -> ""'''.
    */
  def apply(
    extracted: Extracted,
    project: ProjectRef,
    surround: Surround = Surround.empty,
  ): MessageParams = new MessageParams(extracted, project, surround)

  def unapply(params: MessageParams)
    : Option[(Extracted, ProjectRef, Surround)] =
    Some((params.extracted, params.project, params.surround))
}
