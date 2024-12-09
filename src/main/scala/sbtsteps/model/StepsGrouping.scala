package sbtsteps
package model

/** Data type for declaring how project steps should be grouped when aggregated.
  */
sealed trait StepsGrouping

object StepsGrouping {

  /** Group project steps with the same step together. Useful if you want to
    * mimic sbt aggregation.
    */
  case object ByStep extends StepsGrouping

  /** Group project steps by project.
    */
  case object ByProject extends StepsGrouping
}
