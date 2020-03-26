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

object AppDependencies {

  val compileCommon = Seq(
      "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
      "org.slf4j"              %  "slf4j-api" % "1.7.30"
    )

  val compilePlay25 = Seq(
      "uk.gov.hmrc" %% "http-verbs" % "10.6.0-play-25"
    )

  val compilePlay26 = Seq(
      "uk.gov.hmrc" %% "http-verbs" % "10.6.0-play-26"
    )

  val test = Seq(
    "commons-codec"          %  "commons-codec"         % "1.14"     % Test,
    "org.scalatest"          %% "scalatest"             % "3.1.1"    % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"          % "0.35.10"  % Test,
    "org.scalacheck"         %% "scalacheck"            % "1.14.3"   % Test,
    "com.github.tomakehurst" %  "wiremock"              % "2.26.3"   % Test,
    "org.scalatestplus"      %% "scalatestplus-mockito" % "1.0.0-M2" % Test,
    "org.mockito"            %  "mockito-core"          % "3.3.3"    % Test,
    "org.slf4j"              %  "slf4j-simple"          % "1.7.30"   % Test
  )
}
