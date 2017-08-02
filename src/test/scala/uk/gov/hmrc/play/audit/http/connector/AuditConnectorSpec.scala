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

package uk.gov.hmrc.play.audit.http.connector

import org.joda.time.{DateTime, DateTimeZone}
import org.json4s.native.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import uk.gov.hmrc.audit.handler.AuditHandler
import uk.gov.hmrc.audit.serialiser.AuditSerialiserLike
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.http.connector.AuditResult._
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, MergedDataEvent}

import scala.concurrent.ExecutionContext.Implicits.global

class AuditConnectorSpec extends WordSpecLike with MustMatchers with ScalaFutures with MockitoSugar with OneInstancePerTest {

  implicit val formats: Formats = DefaultFormats

  val fakeConfig = AuditingConfig(consumer = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))),
                                      enabled = true,
                                      traceRequests = true)

  val mockDatastreamHandler: AuditHandler = mock[AuditHandler]
  val mockLoggerHandler: AuditHandler = mock[AuditHandler]
  val mockAuditSerialiser: AuditSerialiserLike = mock[AuditSerialiserLike]

  def mockConnector(config: AuditingConfig): AuditConnector = {
    val connector = AuditConnector(config)
    connector.auditSerialiser = mockAuditSerialiser
    connector.datastreamConnector = mockDatastreamHandler
    connector.loggingConnector = mockLoggerHandler
    connector
  }

  "sendLargeMergedEvent" should {
    "call datastream with large merged event" taggedAs Tag("txm80") in {
      val mergedEvent = MergedDataEvent("Test", "Test", "TestEventId",
          DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)),
          DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)))

      val expected = write(mergedEvent)
      when(mockAuditSerialiser.serialise(mergedEvent)).thenReturn(expected)
      when(mockDatastreamHandler.sendEvent(expected)).thenReturn(uk.gov.hmrc.audit.AuditResult.Success)

      mockConnector(fakeConfig).sendMergedEvent(mergedEvent).futureValue mustBe Success
    }
  }

  "sendEvent" should {
    val event = DataEvent("source", "type")
    val expected = write(event)

    "call datastream with the event converted to json" in {
      when(mockAuditSerialiser.serialise(event)).thenReturn(expected)
      when(mockDatastreamHandler.sendEvent(expected)).thenReturn(uk.gov.hmrc.audit.AuditResult.Success)

      mockConnector(fakeConfig).sendEvent(event).futureValue mustBe AuditResult.Success
    }

    "return disabled if auditing is not enabled" in {
      val disabledConfig = AuditingConfig(consumer = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))),
                                          enabled = false,
                                          traceRequests = true)

      mockConnector(disabledConfig).sendEvent(event).futureValue must be (AuditResult.Disabled)

      verifyZeroInteractions(mockDatastreamHandler)
      verifyZeroInteractions(mockLoggerHandler)
    }
  }
}
