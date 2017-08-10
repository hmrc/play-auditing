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

import java.io.IOException
import java.net.ServerSocket

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest._
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.audit.HandlerResult.{Failure, Rejected, Success}

class DatastreamHandlerUnitSpec extends WordSpecLike with Inspectors with Matchers {

  val datastreamHandler = new DatastreamHandler("http", "localhost", 1234,
    "/some/path", 2000, 2000) {
    override def sendHttpRequest(event: String): HttpResult = {
      HttpResult.Response(event.toInt)
    }
  }

  "Any Datastream response" should {
    "Return Success for any response code of 204" in {
      datastreamHandler.sendEvent("204") shouldBe Success
    }

    "Return Failure for any response code of 3XX or 401-412 or 414-499 or 5XX" in {
      forAll((300 to 399) ++ (401 to 412) ++ (414 to 499) ++ (500 to 599)) { code =>
        val result = datastreamHandler.sendEvent(code.toString)
        result shouldBe Failure
      }
    }

    "Return Rejected for any response code of 400 or 413" in {
      forAll(Seq(400, 413)) { code =>
        val result = datastreamHandler.sendEvent(code.toString)
        result shouldBe Rejected
      }
    }
  }
}

class DatastreamHandlerWireSpec extends WordSpecLike with Inspectors with Matchers with BeforeAndAfterEach with BeforeAndAfterAll {

  val datastreamTestPort: Int = availablePort
  val datastreamPath = "/write/audit"
  val datastreamHandler = new DatastreamHandler(
    scheme = "http",
    host = "localhost",
    port = datastreamTestPort,
    path = datastreamPath,
    connectTimeout = 2000,
    requestTimeout = 2000)

  val wireMock = new WireMockServer(datastreamTestPort)

  def availablePort: Int = {
    var port = 9876
    var socket: ServerSocket = null

    try {
      socket = new ServerSocket(0)
      port = socket.getLocalPort
    } catch {
      case ex: IOException =>
    } finally {
      if (socket != null) {
        try {
          socket.close()
        } catch {
          case ex: IOException =>
        }
      }
    }

    port
  }

  override def beforeAll: Unit = {
    WireMock.configureFor("localhost", datastreamTestPort)
    wireMock.start()
  }

  override def afterEach: Unit = {
    WireMock.reset()
  }

  "Successful call to Datastream" should {
    "Return a Success result" in {
      verifySingleCall("SUCCESS", 204, Success)
    }
  }

  "Failed call to Datastream" should {
    "Return a Rejected if Datastream rejected the event as malformed" in {
      verifySingleCall("REJECTED", 400, Rejected)
    }

    "Return a Failure if the POST could not be completed" in {
      verifySingleCall("UNAVAILABLE", 503, Failure)
    }

    "Return a transient Failure if the POST timed out waiting for a response" in {
      WireMock.stubFor(
        post(urlPathEqualTo(datastreamPath))
          .withRequestBody(WireMock.equalTo("TIMEOUT"))
          .willReturn(aResponse().withFixedDelay(3000).withStatus(204)))

      val result = datastreamHandler.sendEvent("TIMEOUT")

      WireMock.verify(1, postRequestedFor(urlPathEqualTo(datastreamPath)))
      result shouldBe Failure
    }
  }

  "Calls to Datastream that return an empty response" should {
    "Retry the POST and return Success if the retried call was ok" in {
      verifyErrorRetry("EMPTY_RESPONSE", Fault.EMPTY_RESPONSE, 204, Success)
    }

    "Retry the POST if the Datastream response was malformed and return Failure" in {
      verifyErrorRetry("EMPTY_RESPONSE", Fault.EMPTY_RESPONSE, 503, Failure)
    }
  }

  "Calls to Datastream that return a bad response" should {
    "Retry the POST and return Success if the retried call was ok" in {
      verifyErrorRetry("RANDOM_DATA_THEN_CLOSE", Fault.RANDOM_DATA_THEN_CLOSE, 204, Success)
    }

    "Retry the POST if the Datastream response was malformed and return Failure" in {
      verifyErrorRetry("RANDOM_DATA_THEN_CLOSE", Fault.RANDOM_DATA_THEN_CLOSE, 503, Failure)
    }
  }

  def stub(event: String, status: Integer): Unit = {
    WireMock.stubFor(
      post(urlPathEqualTo(datastreamPath))
        .withRequestBody(WireMock.equalTo(event))
        .willReturn(aResponse().withStatus(status)))
  }

  def stub(event: String, status: Integer, withScenario: String, toScenario: String): Unit = {
    WireMock.stubFor(
      post(urlPathEqualTo(datastreamPath))
        .inScenario("Scenario")
        .whenScenarioStateIs(withScenario)
        .withRequestBody(WireMock.equalTo(event))
        .willReturn(aResponse().withStatus(status))
        .willSetStateTo(toScenario))
  }

  def stub(event: String, fault: Fault, withScenario: String, toScenario: String): Unit = {
    WireMock.stubFor(
      post(urlPathEqualTo(datastreamPath))
        .inScenario("Scenario")
        .whenScenarioStateIs(withScenario)
        .withRequestBody(WireMock.equalTo(event))
        .willReturn(aResponse().withFault(fault))
        .willSetStateTo(toScenario))
  }

  def verifyErrorRetry(event: String, fault: Fault, retriedResponse: Integer, expectedResult: HandlerResult): Unit = {
    stub(event, fault, Scenario.STARTED, "RETRYING")
    stub(event, retriedResponse, "RETRYING", "FINISHED")

    val result = datastreamHandler.sendEvent(event)

    WireMock.verify(2, postRequestedFor(urlPathEqualTo(datastreamPath)))
    result shouldBe expectedResult
  }

  def verifySingleCall(event: String, responseStatus: Integer, expectedResult: HandlerResult): Unit = {
    stub(event, responseStatus)

    val result = datastreamHandler.sendEvent(event)

    WireMock.verify(1, postRequestedFor(urlPathEqualTo(datastreamPath)))
    result shouldBe expectedResult
  }
}
