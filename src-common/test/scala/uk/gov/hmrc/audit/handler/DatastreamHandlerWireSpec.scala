/*
 * Copyright 2021 HM Revenue & Customs
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

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, post, postRequestedFor, urlPathEqualTo}
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Inspectors}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject.DefaultApplicationLifecycle
import play.api.libs.json.{JsString, JsValue}
import uk.gov.hmrc.audit.{HandlerResult, WireMockUtils, WSClient}

import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global


class DatastreamHandlerWireSpec
  extends AnyWordSpecLike
     with Inspectors
     with Matchers
     with BeforeAndAfterEach
     with BeforeAndAfterAll
     with ScalaFutures
     with IntegrationPatience {

  val datastreamTestPort: Int = WireMockUtils.availablePort
  val datastreamPath = "/write/audit"

  implicit val materializer   = ActorMaterializer()(ActorSystem())
  implicit val lifecycle      = new DefaultApplicationLifecycle()

  val datastreamHandler = new DatastreamHandler(
    scheme         = "http",
    host           = "localhost",
    port           = datastreamTestPort,
    path           = datastreamPath,
    wsClient       = WSClient(
                       connectTimeout = 2000.millis,
                       requestTimeout = 2000.millis,
                       userAgent      = "the-micro-service-name"
                     )
    )


  val wireMock = new WireMockServer(datastreamTestPort)

  override def beforeAll: Unit = {
    WireMock.configureFor("localhost", datastreamTestPort)
    wireMock.start()
  }

  override def afterEach: Unit = {
    WireMock.reset()
  }

  "Successful call to Datastream" should {
    "Return a Success result" in {
      verifySingleCall(JsString("SUCCESS"), 204, HandlerResult.Success)
    }
  }

  "All calls to Datastream" should {
    "set the user-agent" in {
      val event = JsString("EVENT")
      stub(event, 204)
      datastreamHandler.sendEvent(event).futureValue
      WireMock.verify(1, postRequestedFor(urlPathEqualTo(datastreamPath)).withHeader("User-Agent", equalTo("the-micro-service-name")))
    }
  }

  "Failed call to Datastream" should {
    "Return a Rejected if Datastream rejected the event as malformed" in {
      verifySingleCall(JsString("REJECTED"), 400, HandlerResult.Rejected)
    }

    "Return a Failure if the POST could not be completed" in {
      verifySingleCall(JsString("UNAVAILABLE"), 503, HandlerResult.Failure)
    }

    "Return a transient Failure if the POST timed out waiting for a response" in {
      val event = JsString("TIMEOUT")
      WireMock.stubFor(
        post(urlPathEqualTo(datastreamPath))
          .withRequestBody(WireMock.equalTo(event.toString))
          .willReturn(aResponse().withFixedDelay(3000).withStatus(204)))

      whenReady(datastreamHandler.sendEvent(event), timeout(4000.millis)) { result =>
        WireMock.verify(1, postRequestedFor(urlPathEqualTo(datastreamPath)))
        result shouldBe HandlerResult.Failure
      }
    }
  }

  "Calls to Datastream that return an empty response" should {
    "Retry the POST and return Success if the retried call was ok" in {
      verifyErrorRetry(JsString("EMPTY_RESPONSE"), Fault.EMPTY_RESPONSE, 204, HandlerResult.Success)
    }

    "Retry the POST if the Datastream response was malformed and return Failure" in {
      verifyErrorRetry(JsString("EMPTY_RESPONSE"), Fault.EMPTY_RESPONSE, 503, HandlerResult.Failure)
    }
  }

  "Calls to Datastream that return a bad response" should {
    "Retry the POST and return Success if the retried call was ok" in {
      verifyErrorRetry(JsString("RANDOM_DATA_THEN_CLOSE"), Fault.RANDOM_DATA_THEN_CLOSE, 204, HandlerResult.Success)
    }

    "Retry the POST if the Datastream response was malformed and return Failure" in {
      verifyErrorRetry(JsString("RANDOM_DATA_THEN_CLOSE"), Fault.RANDOM_DATA_THEN_CLOSE, 503, HandlerResult.Failure)
    }
  }

  def stub(event: JsValue, status: Integer): Unit =
    WireMock.stubFor(
      post(urlPathEqualTo(datastreamPath))
        .withRequestBody(WireMock.equalTo(event.toString))
        .willReturn(aResponse().withStatus(status)))

  def stub(event: JsValue, status: Integer, withScenario: String, toScenario: String): Unit =
    WireMock.stubFor(
      post(urlPathEqualTo(datastreamPath))
        .inScenario("Scenario")
        .whenScenarioStateIs(withScenario)
        .withRequestBody(WireMock.equalTo(event.toString))
        .willReturn(aResponse().withStatus(status))
        .willSetStateTo(toScenario))

  def stub(event: JsValue, fault: Fault, withScenario: String, toScenario: String): Unit =
    WireMock.stubFor(
      post(urlPathEqualTo(datastreamPath))
        .inScenario("Scenario")
        .whenScenarioStateIs(withScenario)
        .withRequestBody(WireMock.equalTo(event.toString))
        .willReturn(aResponse().withFault(fault))
        .willSetStateTo(toScenario))

  def verifyErrorRetry(event: JsValue, fault: Fault, retriedResponse: Integer, expectedResult: HandlerResult): Unit = {
    stub(event, fault, Scenario.STARTED, "RETRYING")
    stub(event, retriedResponse, "RETRYING", "FINISHED")

    val result = datastreamHandler.sendEvent(event).futureValue

    WireMock.verify(2, postRequestedFor(urlPathEqualTo(datastreamPath)))
    result shouldBe expectedResult
  }

  def verifySingleCall(event: JsValue, responseStatus: Integer, expectedResult: HandlerResult): Unit = {
    stub(event, responseStatus)

    val result = datastreamHandler.sendEvent(event).futureValue

    WireMock.verify(1, postRequestedFor(urlPathEqualTo(datastreamPath)))
    result shouldBe expectedResult
  }
}
