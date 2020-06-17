
import sbt.Keys._
import sbt._
import uk.gov.hmrc.SbtArtifactory
import uk.gov.hmrc.versioning.SbtGitVersioning

val scala2_11 = "2.11.12"
val scala2_12 = "2.12.10"

// Disable multiple project tests running at the same time: https://stackoverflow.com/questions/11899723/how-to-turn-off-parallel-execution-of-tests-for-multi-project-builds
// TODO: restrict parallelExecution to tests only (the obvious way to do this using Test scope does not seem to work correctly)
parallelExecution in ThisBuild := false


lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc",
  majorVersion := 5,
  scalaVersion := scala2_12,
  crossScalaVersions := Seq(scala2_11, scala2_12),
  makePublicallyAvailableOnBintray := true,
  resolvers := Seq(
                 Resolver.bintrayRepo("hmrc", "releases"),
                 Resolver.typesafeRepo("releases")
               ),
  scalacOptions ++= Seq("-feature")
)


lazy val library = (project in file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory)
  .settings(
    commonSettings,
    publish := {},
    publishAndDistribute := {},
    // by default this is Seq(scalaVersion) which doesn't play well and causes sbt
    // to try an invalid cross-build for playAuditingPlay25
    crossScalaVersions := Seq.empty
  )
  .aggregate(
    //playAuditingPlay25,
    playAuditingPlay26,
    playAuditingPlay27
  )

lazy val playAuditingPlay25 = Project("play-auditing-play-25", file("play-auditing-play-25"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    Compile / scalaSource := baseDirectory.value / "../src-common/main/scala",
    Test    / scalaSource := baseDirectory.value / "../src-common/test/scala",
    libraryDependencies ++= AppDependencies.compileCommon ++ AppDependencies.compilePlay25 ++ AppDependencies.test,
    scalaVersion := scala2_11,
    crossScalaVersions := Seq(scala2_11)
  )

lazy val playAuditingPlay26 = Project("play-auditing-play-26", file("play-auditing-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    Compile / scalaSource := baseDirectory.value / "../src-common/main/scala",
    Test    / scalaSource := baseDirectory.value / "../src-common/test/scala",
    libraryDependencies ++= AppDependencies.compileCommon ++ AppDependencies.compilePlay26 ++ AppDependencies.test
  )

lazy val playAuditingPlay27 = Project("play-auditing-play-27", file("play-auditing-play-27"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    Compile / scalaSource := baseDirectory.value / "../src-common/main/scala",
    Test    / scalaSource := baseDirectory.value / "../src-common/test/scala",
    libraryDependencies ++= AppDependencies.compileCommon ++ AppDependencies.compilePlay27 ++ AppDependencies.test
  )
