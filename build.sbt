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

import sbt._
import sbt.Keys._
import uk.gov.hmrc.versioning.SbtGitVersioning
import uk.gov.hmrc._

lazy val microservice: Project = (project in file("."))
  .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)
  .settings(
    name := "play-auditing",
    moduleName := "play-auditing",
    scalacOptions += "-language:implicitConversions",
    libraryDependencies ++= Seq(
      "uk.gov.hmrc" %% "http-core" % "0.5.0",
      "com.ning" % "async-http-client" % "1.8.15",
      "com.typesafe.play" %% "play" % "2.6.15",
      "commons-codec" % "commons-codec" % "1.7" % Test,
      "org.scalatest" %% "scalatest" % "2.2.6" % Test,
      "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % Test,
      "org.scalacheck" %% "scalacheck" % "1.13.1" % Test,
      "org.pegdown" % "pegdown" % "1.5.0" % Test,
      "com.github.tomakehurst" % "wiremock" % "1.52" % Test,
      "org.mockito" % "mockito-all" % "1.10.19" % Test
    ),
    scalaVersion := "2.11.11",
    resolvers := Seq(
      Resolver.bintrayRepo("hmrc", "releases"),
      Resolver.typesafeRepo("releases")
    )
  )
