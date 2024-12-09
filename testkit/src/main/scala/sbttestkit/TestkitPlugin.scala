package sbttestkit

import sbt.*
import sbt.Keys.*

object TestkitPlugin extends AutoPlugin {

  object autoImport extends Testkit {
    implicit class TestKitSyntax(val result: Any) extends AnyVal {
      def shouldBe(expected: Any): Unit = assertEqual(result, expected)
    }
  }

  // enable the plugin manually
  override def trigger: PluginTrigger = noTrigger

  override def globalSettings = Def.settings(
    // use temp directory to publish artifacts, so that they're removed afterwards
    // root of the build is used, so that all (sub)projects publish to the same directory
    publishTo := Some(Resolver.file(
      "tmp-ivy",
      (LocalRootProject / baseDirectory).value / "ivy-repo" / "releases",
    )),
  )
}
