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

  val compilePlay27 = Seq(
      "uk.gov.hmrc" %% "http-verbs" % "10.6.0-play-27"
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
