enablePlugins(CIStepsPlugin)

inThisBuild(Seq(
  scalaVersion := "2.13.14",
  crossScalaVersions := Seq("2.12.18", "2.13.14"),

  ci / steps := Seq(
    +(Test / test) named "Cross test",
    +publishLocal named "Cross publish",
  ),
))

lazy val foo = (project in file("foo"))
  .enablePlugins(CIStepsPlugin)
  .settings(name := "foo")

lazy val bar = (project in file("bar"))
  .enablePlugins(CIStepsPlugin)
  .settings(name := "bar")
  .dependsOn(foo)

lazy val root = (project in file("."))
  .enablePlugins(CIStepsPlugin, ScalaUnidocPlugin)
  .settings(
    name := "root",
    publish / skip := true,
    scalacOptions += "-Werror",
    ci / steps += (Compile / unidoc) named "Generate unified Scaladoc",
  ).aggregate(foo, bar)

