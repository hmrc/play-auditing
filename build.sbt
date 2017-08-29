/*
 * Copyright 2017 HM Revenue & Customs
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

import sbt._
import sbt.Keys.{testFrameworks, _}
import uk.gov.hmrc.versioning.SbtGitVersioning

val appName = "play-auditing"
val Benchmark = config("bench") extend Test

val compileDeps = Seq(
  "uk.gov.hmrc" %% "http-core" % "0.5.0",
  "org.slf4j" % "slf4j-api" % "1.7.25"
)

val testDeps = Seq(
  "org.scalatest" %% "scalatest" % "2.2.6" % Test,
  "org.pegdown" % "pegdown" % "1.5.0" % Test,
  "com.github.tomakehurst" % "wiremock" % "1.52" % Test,
  "org.mockito" % "mockito-all" % "1.10.19" % Test
)

val benchDeps = Seq(
  "com.storm-enroute" %% "scalameter" % "0.8.2" % Benchmark
)

lazy val microservice = (project in file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)
  .configs(Benchmark)
  .settings(
    scalacOptions += "-language:implicitConversions",
    libraryDependencies := compileDeps ++ testDeps ++ benchDeps,
    scalaVersion := "2.11.7",
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      "typesafe-releases" at "http://repo.typesafe.com/typesafe/releases/"
    ),

    testFrameworks in Benchmark += new TestFramework("org.scalameter.ScalaMeterFramework"),
    parallelExecution in Benchmark := false,
    testOptions in Benchmark := Seq(),
    inConfig(Benchmark)(Defaults.testSettings)
  )
