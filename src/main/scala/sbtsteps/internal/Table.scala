package sbtsteps.internal

/** Data type for an html table
  * @see
  *   https://developer.mozilla.org/en-US/docs/Web/HTML/Element/table
  */
case class Table(rows: TableRow*) {
  def toHtml: String = s"<table>${rows.map(_.toHtml).mkString}</table>"
}

/** Data type for an html row ('''<tr></tr>''')
  * @see
  *   https://developer.mozilla.org/en-US/docs/Web/HTML/Element/tr
  */
case class TableRow(cells: TableCell*) {
  def toHtml: String = s"<tr>${cells.map(_.toHtml).mkString}</tr>"
}

/** Data type for an html cell ('''<td></td>''')
  * @param colspan
  *   Value for the '''colspan''' attribute
  * @param title
  *   Value for the '''title''' attribute, shown as tooltip in the browser
  * @param width
  *   Value for the '''width''' attribute
  * @see
  *   https://developer.mozilla.org/en-US/docs/Web/HTML/Element/td
  */
case class TableCell(
  contents: String,
  colspan: Option[Int] = None,
  title: Option[String] = None,
  width: Option[Int] = None,
) {
  def colspan(colspan: Int): TableCell = copy(colspan = Some(colspan))

  def title(title: String): TableCell = copy(title = Some(title))

  def width(width: Int): TableCell = copy(width = Some(width))

  def toHtml: String = {
    val colspanHtml = colspan.map { s => s" colspan=$s" } getOrElse ""
    val titleHtml = title.map { t => s""" title="$t"""" } getOrElse ""
    val widthHtml = width.map { s => s" width=$s" } getOrElse ""
    s"<td$colspanHtml$titleHtml$widthHtml>$contents</td>"
  }
}

/** @define tableEx
  *   {{{ import sbtsteps.internal.Table.*
  *
  * table( tr( td("test" span 2 width 3) ) )}}}
  */
object Table {

  /** Convenience method for creating a table
    *
    * @example
    *   $tableEx
    */
  def table(rows: TableRow*): Table = Table(rows*)

  /** Convenience method for creating a table row
    *
    * @example
    *   \@tableEx
    */
  def tr(cells: TableCell*): TableRow = TableRow(cells*)

  implicit def stringToTableCell(str: String): TableCell = TableCell(str)
}
