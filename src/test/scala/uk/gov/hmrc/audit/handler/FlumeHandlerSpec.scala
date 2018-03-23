/*
 * Copyright 2018 HM Revenue & Customs
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

import com.github.tomakehurst.wiremock.client.{MappingBuilder, WireMock}
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest._
import uk.gov.hmrc.audit.AuditResult
import uk.gov.hmrc.audit.AuditResult.{Failure, Rejected, Success}

class FlumeHandlerUnitSpec extends WordSpecLike with Inspectors with Matchers {

  val flumeUrl = new URL(s"http://localhost:1234/")
  val flumeHandler: FlumeHandler = new FlumeHandler(flumeUrl, 2000, 2000) {
    override def sendHttpRequest(event: String): HttpResult = {
      HttpResult.Response(event.toInt)
    }
  }

  "Any Flume response" should {
    "Return Success for any response code of 200" in {
      flumeHandler.sendEvent("200") shouldBe Success
    }

    "Return Failure for any response code of 5XX" in {
      forAll(500 to 599) { code =>
        val result = flumeHandler.sendEvent(code.toString)
        result shouldBe Failure
      }
    }

    "Return Rejected for any response code of 400" in {
      flumeHandler.sendEvent("400") shouldBe Rejected
    }
  }
}

class FlumeHandlerWireSpec extends WordSpecLike with Matchers with WireMockTestSuite {

  val flumeUrl = new URL(s"http://localhost:$wireMockPort/")
  val flumeHandler = new FlumeHandler(
    flumeUrl,
    connectTimeout = 2000,
    requestTimeout = 2000)

  "Successful call to Flume" should {
    "Return a Success result" in {
      verifySingleCall("SUCCESS", 200, Success)
    }
  }

  "Failed call to Flume" should {
    "Return a Rejected if Flume rejected the event as malformed" in {
      verifySingleCall("REJECTED", 400, Rejected)
    }

    "Return a Failure if the POST could not be completed" in {
      verifySingleCall("UNAVAILABLE", 503, Failure)
    }

    "Return a transient Failure if the POST timed out waiting for a response" in {
      WireMock.stubFor(
        flumePost
          .withRequestBody(WireMock.equalTo("TIMEOUT"))
          .willReturn(aResponse().withFixedDelay(3000).withStatus(204)))

      val result = flumeHandler.sendEvent("TIMEOUT")

      WireMock.verify(1, postRequestedFor(urlPathEqualTo("/")))
      result shouldBe Failure
    }
  }

  "Calls to Flume that return an empty response" should {
    "Retry the POST and return Success if the retried call was ok" in {
      verifyErrorRetry("EMPTY_RESPONSE", Fault.EMPTY_RESPONSE, 200, Success)
    }

    "Retry the POST if the Flume response was malformed and return Failure" in {
      verifyErrorRetry("EMPTY_RESPONSE", Fault.EMPTY_RESPONSE, 503, Failure)
    }
  }

  "Calls to Flume that return a bad response" should {
    "Retry the POST and return Success if the retried call was ok" in {
      verifyErrorRetry("RANDOM_DATA_THEN_CLOSE", Fault.RANDOM_DATA_THEN_CLOSE, 200, Success)
    }

    "Retry the POST if the Flume response was malformed and return Failure" in {
      verifyErrorRetry("RANDOM_DATA_THEN_CLOSE", Fault.RANDOM_DATA_THEN_CLOSE, 503, Failure)
    }
  }

  def flumePost: MappingBuilder = {
    post(urlPathEqualTo("/"))
  }

  def stub(event: String, status: Integer): Unit = {
    WireMock.stubFor(
      flumePost
        .withRequestBody(WireMock.equalTo(event))
        .willReturn(aResponse().withStatus(status)))
  }

  def stub(event: String, status: Integer, withScenario: String, toScenario: String): Unit = {
    WireMock.stubFor(
      flumePost
        .inScenario("Scenario")
        .whenScenarioStateIs(withScenario)
        .withRequestBody(WireMock.equalTo(event))
        .willReturn(aResponse().withStatus(status))
        .willSetStateTo(toScenario))
  }

  def stub(event: String, fault: Fault, withScenario: String, toScenario: String): Unit = {
    WireMock.stubFor(
      flumePost
        .inScenario("Scenario")
        .whenScenarioStateIs(withScenario)
        .withRequestBody(WireMock.equalTo(event))
        .willReturn(aResponse().withFault(fault))
        .willSetStateTo(toScenario))
  }

  def verifyErrorRetry(event: String, fault: Fault, retriedResponse: Integer, expectedResult: AuditResult): Unit = {
    stub(event, fault, Scenario.STARTED, "RETRYING")
    stub(event, retriedResponse, "RETRYING", "FINISHED")

    val result = flumeHandler.sendEvent(event)

    WireMock.verify(2, postRequestedFor(urlPathEqualTo("/")))
    result shouldBe expectedResult
  }

  def verifySingleCall(event: String, responseStatus: Integer, expectedResult: AuditResult): Unit = {
    stub(event, responseStatus)

    val result = flumeHandler.sendEvent(event)

    WireMock.verify(1, postRequestedFor(urlPathEqualTo("/")))
    result shouldBe expectedResult
  }
}
