import sbt._

object LibDependencies {
  // we depend on http-verbs just to integrate via the AuditHooks
  // http calls are made with the underlying play-ws
  val httpVerbsVersion = "14.13.0"

  val common = Seq(
    "org.scalatest"          %% "scalatest"               % "3.2.17"       % Test,
    "com.vladsch.flexmark"   %  "flexmark-all"            % "0.64.8"       % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"         % "3.2.17.0"     % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.17.29"      % Test
  )

  val play28 = Seq(
    "uk.gov.hmrc"            %% "http-verbs-play-28" % httpVerbsVersion,
    "com.github.tomakehurst" %  "wiremock-jre8"      % "2.27.2"      % Test,
    "org.slf4j"              %  "slf4j-simple"       % "1.7.30"      % Test
  )

  val play29 = Seq(
    "uk.gov.hmrc"            %% "http-verbs-play-29" % httpVerbsVersion,
    "com.github.tomakehurst" %  "wiremock"           % "3.0.0-beta-7" % Test,
    "org.slf4j"              %  "slf4j-simple"       % "2.0.7"        % Test
  )

  val play30 = Seq(
    "uk.gov.hmrc"            %% "http-verbs-play-30" % httpVerbsVersion,
    "com.github.tomakehurst" %  "wiremock"           % "3.0.0-beta-7" % Test,
    "org.slf4j"              %  "slf4j-simple"       % "2.0.7"        % Test
  )
}
