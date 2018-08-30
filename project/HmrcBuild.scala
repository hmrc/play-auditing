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

object HmrcBuild extends Build {

  import uk.gov.hmrc._

  val appName = "play-auditing"

  lazy val microservice: Project = Project(appName, file("."))
    .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)
    .settings(
      scalacOptions += "-language:implicitConversions",
      libraryDependencies ++= AppDependencies(),
      scalaVersion := "2.11.7",
      resolvers := Seq(
        Resolver.bintrayRepo("hmrc", "releases"),
        "typesafe-releases" at "http://repo.typesafe.com/typesafe/releases/"
      )
    )
}

private object AppDependencies {

  val compile = Seq(
    "uk.gov.hmrc" %% "http-core" % "0.7.0",
    "org.slf4j" % "slf4j-api" % "1.7.5"
  )

  trait TestDependencies {
    lazy val scope: String = "test"
    lazy val test: Seq[ModuleID] = ???
  }

  object Test {
    def apply(): Seq[ModuleID] = new TestDependencies {
      override lazy val test = Seq(
        "commons-codec" % "commons-codec" % "1.7" % scope,
        "org.scalatest" %% "scalatest" % "2.2.6" % scope,
        "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % scope,
        "org.scalacheck" %% "scalacheck" % "1.13.1" % scope,
        "org.pegdown" % "pegdown" % "1.5.0" % scope,
        "com.github.tomakehurst" % "wiremock" % "1.52" % scope,
        "org.mockito" % "mockito-all" % "1.10.19" % scope
      )
    }.test
  }

  def apply(): Seq[ModuleID] = compile ++ Test()
}
