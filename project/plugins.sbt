libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

val sttpV = "3.10.2"
libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % sttpV
libraryDependencies += "com.softwaremill.sttp.client3" %% "upickle" % sttpV

addSbtPlugin("io.github.agboom" % "sbt-steps" % "0.2.0")
addSbtPlugin("ch.epfl.scala" % "sbt-version-policy" % "3.2.1")
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.0")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.12.2")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.2")
