/*
 * Copyright 2015 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt.Keys._
import sbt._
import uk.gov.hmrc.SbtArtifactory
import uk.gov.hmrc.versioning.SbtGitVersioning

val scala2_11 = "2.11.12"
val scala2_12 = "2.12.10"

lazy val commonSettings = Seq(
  organization := "uk.gov.hmrc",
  majorVersion := 5,
  scalaVersion := scala2_12,
  crossScalaVersions := Seq(scala2_11, scala2_12),
  makePublicallyAvailableOnBintray := true,
  resolvers := Seq(
                 Resolver.bintrayRepo("hmrc", "releases"),
                 Resolver.typesafeRepo("releases")
               )
)


lazy val library = (project in file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory)
  .settings(
    commonSettings,
    publish := {},
    publishAndDistribute := {},
    // by default this is Seq(scalaVersion) which doesn't play well and causes sbt
    // to try an invalid cross-build for hmrcMongoMetrixPlay27
    crossScalaVersions := Seq.empty
  )
  .aggregate(
    playAuditingPlay25,
    playAuditingPlay26
  )

lazy val playAuditingPlay25 = Project("play-auditing-play-25", file("play-auditing-play-25"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    Compile / scalaSource := baseDirectory.value / "../src/main/scala",
    Test    / scalaSource := baseDirectory.value / "../src/test/scala",
    libraryDependencies ++= AppDependencies.compileCommon ++ AppDependencies.compilePlay25 ++ AppDependencies.test,
    crossScalaVersions := Seq(scala2_11)
  )

lazy val playAuditingPlay26 = Project("play-auditing-play-26", file("play-auditing-play-26"))
  .enablePlugins(SbtAutoBuildPlugin, SbtArtifactory)
  .settings(
    commonSettings,
    Compile / scalaSource := baseDirectory.value / "../src/main/scala",
    Test    / scalaSource := baseDirectory.value / "../src/test/scala",
    libraryDependencies ++= AppDependencies.compileCommon ++ AppDependencies.compilePlay26 ++ AppDependencies.test
  )
