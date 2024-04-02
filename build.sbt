import sbt.Keys._
import sbt._

val scala2_12 = "2.12.18"
val scala2_13 = "2.13.12"
val scala3    = "3.3.3"

ThisBuild / majorVersion     := 8
ThisBuild / scalaVersion     := scala2_13
ThisBuild / isPublicArtefact := true
ThisBuild / scalacOptions    ++= Seq("-feature") ++
                                   (CrossVersion.partialVersion(scalaVersion.value) match {
                                     case Some((3, _ )) => Seq("-explain")
                                     case _             => Seq.empty
                                   })

lazy val library = (project in file("."))
  .settings(publish / skip := true)
  .aggregate(
    playAuditingPlay28,
    playAuditingPlay29,
    playAuditingPlay30
  )

def copyPlay30Sources(module: Project) =
  CopySources.copySources(
    module,
    transformSource   = _.replace("org.apache.pekko", "akka"),
    transformResource = _.replace("pekko", "akka")
  )

lazy val playAuditingPlay28 = Project("play-auditing-play-28", file("play-auditing-play-28"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    crossScalaVersions := Seq(scala2_12, scala2_13),
    copyPlay30Sources(playAuditingPlay30),
    libraryDependencies ++= LibDependencies.common ++ LibDependencies.play28
  )
  .settings( // https://github.com/sbt/sbt-buildinfo
    buildInfoKeys    := Seq[BuildInfoKey](version),
    buildInfoPackage := "uk.gov.hmrc.audit"
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
