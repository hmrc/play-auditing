
import sbt.Keys._
import sbt._

val scala2_12 = "2.12.15"
val scala2_13 = "2.13.7"

lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc",
  majorVersion := 7,
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
  .settings(
    commonSettings,
    Compile / unmanagedSourceDirectories   += baseDirectory.value / "../src-common/main/scala",
    Compile / unmanagedResourceDirectories += baseDirectory.value / "../src-common/main/resources",
    Test    / unmanagedSourceDirectories   += baseDirectory.value / "../src-common/test/scala",
    Test    / unmanagedResourceDirectories += baseDirectory.value / "../src-common/test/resources",
    libraryDependencies ++= LibDependencies.compileCommon ++ LibDependencies.compilePlay28 ++ LibDependencies.test
  )
