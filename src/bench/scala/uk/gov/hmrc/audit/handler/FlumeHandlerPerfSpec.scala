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

package uk.gov.hmrc.audit.handler

import java.net.URL

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, urlPathEqualTo}
import org.scalameter.api._
import org.scalameter.execution.SeparateJvmsExecutor
import org.scalameter.picklers.Implicits.doublePickler

object FlumeHandlerPerfSpec extends Bench[Double] with WireMockMethods {
  def reporter = new LoggingReporter[Double]
  def persistor = Persistor.None
  def measurer = new Measurer.Default
  def executor = SeparateJvmsExecutor(
    new Executor.Warmer.Default,
    Aggregator.median[Double],
    measurer
  )

  def handler = new FlumeHandler(new URL(s"http://localhost:$wireMockPort/"), 2000, 2000)

  val contentLengths: Gen[Int] = Gen.range("content-length")(50000, 500000, 45000)
  val requests: Gen[String] = for {
    length <- contentLengths
  } yield Array.fill(length)('a').mkString

  performance of "FlumeHandlerThroughput" in {
    measure method "sendEvent" config (
      exec.benchRuns -> 10,
      exec.independentSamples -> 1,
      exec.minWarmupRuns -> 2,
      exec.maxWarmupRuns -> 2
    ) in {
      using(requests) beforeTests {
        startWireMock()
        WireMock.stubFor(post(urlPathEqualTo("/")).willReturn(aResponse().withStatus(200)))
      } afterTests {
        stopWireMock()
      } in {
        request => handler.sendEvent(request)
      }
    }
  }
}
