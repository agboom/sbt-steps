package sbtsteps
package model

/** Strings to surround words with for readability or emphasis.
  *
  * @param prefix
  *   String to put before a word
  * @param postfix
  *   String to put after a word
  * @example
  *   {{{
  *     Surround("<code>", "</code>")("publish") == "<code>publish</code>"
  *   }}}
  */
case class Surround(prefix: String, postfix: String) {

  /** Append a surround inside this surround.
    * @example
    *   {{{
    * ("<b>" -> "</b>").appendInside("<code>" -> "</code>").apply("publish")
    * == "<b><code>publish</code></b>"
    *   }}}
    */
  def appendInside(surround: Surround): Surround =
    Surround(s"$prefix${surround.prefix}", s"${surround.postfix}$postfix")

  /** Append a surround outside this surround.
    * @example
    *   {{{
    * ("<code>" -> "</code>").appendOutside("<b>" -> "</b>").apply("publish")
    * == "<b><code>publish</code></b>"
    *   }}}
    */
  def appendOutside(surround: Surround): Surround =
    Surround(s"${surround.prefix}$prefix", s"$postfix${surround.postfix}")

  /** Apply this surround to the given argument.
    */
  def apply(obj: Any): String = s"$prefix$obj$postfix"
}

object Surround {

  /** Create [[Surround]] with the empty string as prefix and postfix.
    */
  def empty: Surround = Surround("", "")

  /** Create [[Surround]] with the given string as both prefix and postfix.
    */
  def apply(surround: String): Surround = Surround(surround, surround)

  implicit def tupleToSurround(tup: (String, String)): Surround =
    Surround(tup._1, tup._2)
}
