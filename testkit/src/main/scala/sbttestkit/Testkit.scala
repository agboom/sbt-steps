package sbttestkit

import sbt.*

trait Testkit {

  def assertExists(file: File): Unit = {
    assert(file.exists(), s"Expected $file to exist")
  }

  def assertNotExists(file: File): Unit = {
    assert(!file.exists(), s"Expected $file not to exist")
  }

  def assertEqual(result: Any, expected: Any): Unit = {
    assert(result == expected, s"expected $result to be $expected")
  }

  def assertContains(result: String, expectedContains: String): Unit = {
    assert(result.contains(expectedContains), s"expected $result to contain `$expectedContains`")
  }

  def localPath(baseDir: File): String =
    (baseDir / "ivy-repo" / "releases").toURI.toString
}
