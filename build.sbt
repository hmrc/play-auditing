import sbt.Keys._
import sbt._

val scala2_12 = "2.12.15"
val scala2_13 = "2.13.8"

lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc",
  majorVersion := 8,
  scalaVersion := scala2_12,
  crossScalaVersions := Seq(scala2_12, scala2_13),
  isPublicArtefact := true,
  scalacOptions ++= Seq("-feature")
)

lazy val library = (project in file("."))
  .settings(
    commonSettings,
    publish / skip := true
  )
  .aggregate(
    playAuditingPlay28
  )

lazy val playAuditingPlay28 = Project("play-auditing-play-28", file("play-auditing-play-28"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    commonSettings,
    Compile / unmanagedSourceDirectories   += baseDirectory.value / "../src-common/main/scala",
    Compile / unmanagedResourceDirectories += baseDirectory.value / "../src-common/main/resources",
    Test    / unmanagedSourceDirectories   += baseDirectory.value / "../src-common/test/scala",
    Test    / unmanagedResourceDirectories += baseDirectory.value / "../src-common/test/resources",
    libraryDependencies ++= LibDependencies.compileCommon ++ LibDependencies.compilePlay28 ++ LibDependencies.test
  )
  .settings( // https://github.com/sbt/sbt-buildinfo
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "uk.gov.hmrc.audit"
   )
