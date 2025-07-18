import sbt.Keys._
import sbt._

val scala2_13 = "2.13.16"
val scala3    = "3.3.6"

ThisBuild / majorVersion     := 9
ThisBuild / scalaVersion     := scala2_13
ThisBuild / isPublicArtefact := true
ThisBuild / scalacOptions    ++= Seq("-feature")

lazy val library = (project in file("."))
  .settings(publish / skip := true)
  .aggregate(
    playAuditingPlay29,
    playAuditingPlay30
  )

def copyPlay30Sources(module: Project) =
  CopySources.copySources(
    module,
    transformSource   = _.replace("org.apache.pekko", "akka"),
    transformResource = _.replace("pekko", "akka")
  )

lazy val playAuditingPlay29 = Project("play-auditing-play-29", file("play-auditing-play-29"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    crossScalaVersions := Seq(scala2_13),
    copyPlay30Sources(playAuditingPlay30),
    libraryDependencies ++= LibDependencies.common ++ LibDependencies.play29
  )
  .settings( // https://github.com/sbt/sbt-buildinfo
    buildInfoKeys    := Seq[BuildInfoKey](version),
    buildInfoPackage := "uk.gov.hmrc.audit"
   )

lazy val playAuditingPlay30 = Project("play-auditing-play-30", file("play-auditing-play-30"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    crossScalaVersions := Seq(scala2_13, scala3),
    libraryDependencies ++= LibDependencies.common ++ LibDependencies.play30,
    // without this, DatastreamHandlerWireSpec sometimes fails with `play.shaded.ahc.io.netty.handler.codec.EncoderException: java.lang.OutOfMemoryError: Direct buffer memory`
    Test / fork := true
  )
  .settings( // https://github.com/sbt/sbt-buildinfo
    buildInfoKeys    := Seq[BuildInfoKey](version),
    buildInfoPackage := "uk.gov.hmrc.audit"
   )
