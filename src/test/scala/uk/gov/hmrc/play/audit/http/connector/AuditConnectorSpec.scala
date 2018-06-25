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

package uk.gov.hmrc.play.audit.http.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, containing, post, postRequestedFor, urlPathEqualTo}
import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import play.api.libs.json.Json
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.audit.handler.AuditHandler
import uk.gov.hmrc.audit.serialiser.{AuditSerialiser, AuditSerialiserLike}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.http.connector.AuditResult._
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}

class AuditConnectorSpec extends WordSpecLike with MustMatchers with ScalaFutures with MockitoSugar with OneInstancePerTest {

  import scala.concurrent.ExecutionContext.Implicits.global

  val consumer = Consumer(BaseUri("datastream-base-url", 8080, "http"))
  val enabledConfig = AuditingConfig(consumer = Some(consumer), enabled = true)
  val disabledConfig = AuditingConfig(consumer = Some(consumer), enabled = false)

  val mockSimpleDatastreamHandler: AuditHandler = mock[AuditHandler]
  val mockMergedDatastreamHandler: AuditHandler = mock[AuditHandler]

  val mockFlumeHandler: AuditHandler = mock[AuditHandler]
  val mockLoggingHandler: AuditHandler = mock[AuditHandler]

  def mockConnector(config: AuditingConfig) = new AuditConnector {
    override def auditingConfig: AuditingConfig = config
    override def simpleDatastreamHandler: AuditHandler = mockSimpleDatastreamHandler
    override def mergedDatastreamHandler: AuditHandler = mockMergedDatastreamHandler
    override def loggingConnector: AuditHandler = mockLoggingHandler
    override def auditSerialiser: AuditSerialiserLike = AuditSerialiser
  }

  "creating an AuditConnector" should {
    "allow the configuration to be specified" in {
      val testPort = 9876
      val consumer = Consumer(BaseUri("localhost", testPort, "http"))
      val config = AuditingConfig(consumer = Some(consumer), enabled = true)
      val connector = new AuditConnector {
        override def auditingConfig: AuditingConfig = config
      }
      val dataCall = DataCall(Map(), Map(), DateTime.now())

      val wireMock = new WireMockServer(testPort)
      WireMock.configureFor("localhost", testPort)
      wireMock.start()

      WireMock.stubFor(
        post(urlPathEqualTo("/write/audit"))
          .withRequestBody(containing("DATA_EVENT"))
          .willReturn(aResponse().withStatus(204)))

      WireMock.stubFor(
        post(urlPathEqualTo("/write/audit/merged"))
          .withRequestBody(containing("MERGED_DATA_EVENT"))
          .willReturn(aResponse().withStatus(204)))

      connector.sendEvent(DataEvent("test", "DATA_EVENT")).futureValue
      WireMock.verify(1, postRequestedFor(urlPathEqualTo("/write/audit")))
      WireMock.reset()

      connector.sendMergedEvent(MergedDataEvent("test", "MERGED_DATA_EVENT", request = dataCall, response = dataCall)).futureValue
      WireMock.verify(1, postRequestedFor(urlPathEqualTo("/write/audit/merged")))

      wireMock.stop()
    }
  }

  "sendMergedEvent" should {
    "call merged Datastream with event converted to json" in {
      when(mockMergedDatastreamHandler.sendEvent(anyString())).thenReturn(HandlerResult.Success)

      val mergedEvent = MergedDataEvent("Test", "Test", "TestEventId",
          DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)),
          DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)))

      mockConnector(enabledConfig).sendMergedEvent(mergedEvent).futureValue mustBe Success

      verify(mockMergedDatastreamHandler).sendEvent(anyString())
      verifyZeroInteractions(mockSimpleDatastreamHandler)
      verifyZeroInteractions(mockFlumeHandler)
      verifyZeroInteractions(mockLoggingHandler)
    }
  }

  "sendEvent" should {
    val event = DataEvent("source", "type")

    "call Datastream with the event converted to json" in {
      when(mockSimpleDatastreamHandler.sendEvent(anyString())).thenReturn(HandlerResult.Success)

      mockConnector(enabledConfig).sendEvent(event).futureValue mustBe AuditResult.Success

      verify(mockSimpleDatastreamHandler).sendEvent(anyString())
      verifyZeroInteractions(mockFlumeHandler)
      verifyZeroInteractions(mockLoggingHandler)
    }

    "return Disabled if auditing is not enabled" in {
      val disabledConfig = AuditingConfig(consumer = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))), enabled = false)

      mockConnector(disabledConfig).sendEvent(event).futureValue must be(AuditResult.Disabled)

      verifyZeroInteractions(mockSimpleDatastreamHandler)
      verifyZeroInteractions(mockFlumeHandler)
      verifyZeroInteractions(mockLoggingHandler)
    }
  }

  "sendExtendedEvent" should {
    "call Datastream with extended event data converted to json" in {
      when(mockSimpleDatastreamHandler.sendEvent(anyString())).thenReturn(HandlerResult.Success)

      val detail = Json.parse( """{"some-event": "value", "some-other-event": "other-value"}""")
      val event: ExtendedDataEvent = ExtendedDataEvent(auditSource = "source", auditType = "type", detail = detail)

      mockConnector(enabledConfig).sendExtendedEvent(event).futureValue mustBe AuditResult.Success

      verify(mockSimpleDatastreamHandler).sendEvent(anyString())
      verifyZeroInteractions(mockFlumeHandler)
      verifyZeroInteractions(mockLoggingHandler)
    }
  }
}
