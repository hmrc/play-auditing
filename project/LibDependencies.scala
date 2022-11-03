import sbt._

object LibDependencies {

  val compileCommon = Seq(
    "org.scala-lang.modules" %% "scala-xml" % "1.3.0",
    "org.slf4j"              %  "slf4j-api" % "1.7.30"
  )

  // we depend on http-verbs just to integrate via the AuditHooks
  // http calls are made with the underlying play-ws
  val httpVerbsVersion = "14.8.0"

  val compilePlay28 = Seq(
    "uk.gov.hmrc" %% "http-verbs-play-28" % httpVerbsVersion
  )

  val test = Seq(
    "org.scalatest"          %% "scalatest"               % "3.2.3"   % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"            % "0.35.10" % Test,
    "org.scalacheck"         %% "scalacheck"              % "1.15.2"  % Test,
    "com.github.tomakehurst" %  "wiremock"                % "2.26.3"  % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.16.46" % Test,
    "org.slf4j"              %  "slf4j-simple"            % "1.7.30"  % Test
  )
}
