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

import PlayCrossCompilation._
import sbt._

private object AppDependencies {

  val compile = dependencies(
    shared = Seq(
      "uk.gov.hmrc" %% "time"       % "3.2.0"
    ),
    play25 = Seq(
      "org.slf4j"   % "slf4j-api"   % "1.7.5",
      "uk.gov.hmrc" %% "http-verbs" % "8.10.0-play-25"
    ),
    play26 = Seq(
      "org.slf4j"   % "slf4j-api"   % "1.7.25",
      "uk.gov.hmrc" %% "http-verbs" % "8.8.0-play-26"
    )
  )

  val test = Seq(
    "commons-codec"          % "commons-codec" % "1.7"     % Test,
    "org.scalatest"          %% "scalatest"    % "3.0.5"   % Test,
    "org.scalacheck"         %% "scalacheck"   % "1.13.4"  % Test,
    "org.pegdown"            % "pegdown"       % "1.5.0"   % Test,
    "com.github.tomakehurst" % "wiremock"      % "1.52"    % Test,
    "org.mockito"            % "mockito-all"   % "1.10.19" % Test
  )
}
