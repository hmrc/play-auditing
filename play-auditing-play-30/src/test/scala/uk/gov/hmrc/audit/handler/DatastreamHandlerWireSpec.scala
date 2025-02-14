/*
 * Copyright 2023 HM Revenue & Customs
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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, post, postRequestedFor, urlPathEqualTo}
import com.github.tomakehurst.wiremock.http.Fault
import org.apache.pekko.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Inspectors}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import play.api.libs.json.{JsString, JsValue}
import uk.gov.hmrc.audit.{DatastreamMetricsMock, HandlerResult, WSClient, WireMockUtils}

import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global

class DatastreamHandlerWireSpec
  extends AnyWordSpec
     with Inspectors
     with Matchers
     with BeforeAndAfterEach
     with BeforeAndAfterAll
     with ScalaFutures
     with IntegrationPatience
     with DatastreamMetricsMock {

  val datastreamTestPort: Int = WireMockUtils.availablePort()
  val datastreamPath = "/write/audit"

  implicit val system   : ActorSystem          = ActorSystem()
  implicit val lifecycle: ApplicationLifecycle = new DefaultApplicationLifecycle()

  trait Test {
    val wsClient = WSClient(
      connectTimeout = 2000.millis,
      requestTimeout = 2000.millis,
      userAgent      = "the-micro-service-name"
    )

    val datastreamHandler = new DatastreamHandler(
      scheme         = "http",
      host           = "localhost",
      port           = datastreamTestPort,
      path           = datastreamPath,
      wsClient       = wsClient,
      metrics        = mockDatastreamMetrics(Some("play.some-application"))
    )
  }

  val wireMock = new WireMockServer(datastreamTestPort)

  override def beforeAll(): Unit = {
    WireMock.configureFor("localhost", datastreamTestPort)
    wireMock.start()
  }

  override def afterEach(): Unit = {
    WireMock.reset()
  }

  "Successful call to Datastream" should {
    "return a Success result" in {
      verifySingleCall(JsString("SUCCESS"), 204, HandlerResult.Success)
    }
  }

  "All calls to Datastream" should {
    "set the user-agent" in new Test {
      val event = JsString("EVENT")
      stub(event, 204)
      datastreamHandler.sendEvent(event).futureValue
      WireMock.verify(1, postRequestedFor(urlPathEqualTo(datastreamPath)).withHeader("User-Agent", equalTo("the-micro-service-name")))
    }
  }

  "Failed call to Datastream" should {
    "return a Rejected if Datastream rejected the event as malformed" in new Test {
      verifySingleCall(JsString("REJECTED"), 400, HandlerResult.Rejected)
    }

    "return a Failure if the POST could not be completed" in new Test {
      verifySingleCall(JsString("UNAVAILABLE"), 503, HandlerResult.Failure)
    }

    "return a transient Failure if the POST timed out waiting for a response" in new Test {
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

    "return a failure if the WSClient is in IllegalStateException: Closed state " in new Test {
      wsClient.close()
      datastreamHandler.sendEvent(JsString("CLOSED")).futureValue shouldBe HandlerResult.Failure
    }
  }

  "Calls to Datastream that return an empty response" should {
    "not retry the POST (beyond default lib retries) and return a failure" in {
      verifyOnlyDefaultLibraryRetries(JsString("EMPTY_RESPONSE"), Fault.EMPTY_RESPONSE)
    }
  }

  "Calls to Datastream that return a bad response" should {
    "not retry the POST (beyond default lib retries) and return a failure" in {
      verifyOnlyDefaultLibraryRetries(JsString("RANDOM_DATA_THEN_CLOSE"), Fault.RANDOM_DATA_THEN_CLOSE)
    }
  }

  private def stub(event: JsValue, status: Integer): Unit =
    WireMock.stubFor(
      post(urlPathEqualTo(datastreamPath))
        .withRequestBody(WireMock.equalTo(event.toString))
        .willReturn(aResponse().withStatus(status))
    )

  private def stub(event: JsValue, fault: Fault): Unit =
    WireMock.stubFor(
      post(urlPathEqualTo(datastreamPath))
        .withRequestBody(WireMock.equalTo(event.toString))
        .willReturn(aResponse().withFault(fault))
    )

  private def verifyOnlyDefaultLibraryRetries(event: JsValue, fault: Fault): Unit = new Test {
    stub(event, fault)
    val result = datastreamHandler.sendEvent(event).futureValue

    result shouldBe HandlerResult.Failure

    // These 6 attempts represent the default retries coming from the StandaloneAhcWSClient (play.ws.ahc.maxRequestRetry) + our original request
    WireMock.verify(6, postRequestedFor(urlPathEqualTo(datastreamPath)))
  }

  private def verifySingleCall(event: JsValue, responseStatus: Integer, expectedResult: HandlerResult): Unit = new Test {
    stub(event, responseStatus)

    val result = datastreamHandler.sendEvent(event).futureValue
    result shouldBe expectedResult

    WireMock.verify(1, postRequestedFor(urlPathEqualTo(datastreamPath)))
  }
}
