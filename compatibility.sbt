/* See [[https://github.com/scalacenter/sbt-version-policy#1-set-versionpolicyintention]]
 * on when and how to change this setting.
 *
 * Every CI build, the binary compatibility is checked against the intention.
 * During release, the released version is checked against the intention.
 */
ThisBuild / versionPolicyIntention := Compatibility.BinaryAndSourceCompatible
