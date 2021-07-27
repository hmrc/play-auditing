
import sbt.Keys._
import sbt._

val scala2_12 = "2.12.12"

lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc",
  majorVersion := 7,
  scalaVersion := scala2_12,
  isPublicArtefact := true,
  scalacOptions ++= Seq("-feature")
)

lazy val library = (project in file("."))
  .settings(
    commonSettings,
    publish := {},
    // by default this is Seq(scalaVersion) which doesn't play well and causes sbt
    // to try an invalid cross-build for playAuditingPlay25
    crossScalaVersions := Seq.empty
  )
  .aggregate(
    playAuditingPlay26,
    playAuditingPlay27,
    playAuditingPlay28
  )

lazy val playAuditingPlay26 = Project("play-auditing-play-26", file("play-auditing-play-26"))
  .settings(
    commonSettings,
    Compile / unmanagedSourceDirectories   += baseDirectory.value / "../src-common/main/scala",
    Compile / unmanagedResourceDirectories += baseDirectory.value / "../src-common/main/resources",
    Test    / unmanagedSourceDirectories   += baseDirectory.value / "../src-common/test/scala",
    Test    / unmanagedResourceDirectories += baseDirectory.value / "../src-common/test/resources",
    libraryDependencies ++= LibDependencies.compileCommon ++ LibDependencies.compilePlay26 ++ LibDependencies.test
  )

lazy val playAuditingPlay27 = Project("play-auditing-play-27", file("play-auditing-play-27"))
  .settings(
    commonSettings,
    Compile / unmanagedSourceDirectories   += baseDirectory.value / "../src-common/main/scala",
    Compile / unmanagedResourceDirectories += baseDirectory.value / "../src-common/main/resources",
    Test    / unmanagedSourceDirectories   += baseDirectory.value / "../src-common/test/scala",
    Test    / unmanagedResourceDirectories += baseDirectory.value / "../src-common/test/resources",
    libraryDependencies ++= LibDependencies.compileCommon ++ LibDependencies.compilePlay27 ++ LibDependencies.test
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
