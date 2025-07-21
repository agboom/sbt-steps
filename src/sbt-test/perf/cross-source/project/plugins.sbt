sys.props.get("plugin.version") match {
  case Some(x) =>
    Seq(
      addSbtPlugin("io.github.agboom" % "sbt-steps"   % x),
      addSbtPlugin("io.github.agboom" % "sbt-testkit" % x),
    )
  case _ => sys.error("""|The system property 'plugin.version' is not defined.
                         |Specify this property using the scriptedLaunchOpts -D."""
      .stripMargin)
}
