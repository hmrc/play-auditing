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

import java.util.UUID

import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import uk.gov.hmrc.audit.AuditResult.Success
import uk.gov.hmrc.audit.model.AuditEvent
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.RequestId
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, MergedDataEvent}

import scala.concurrent.Future

class AuditConnectorSpec extends WordSpecLike with MustMatchers with ScalaFutures with MockitoSugar with OneInstancePerTest {

  import scala.concurrent.ExecutionContext.Implicits.global

  val consumer = Consumer(BaseUri("datastream-base-url", 8080, "http"))
  val enabledConfig = AuditingConfig(consumer = Some(consumer), enabled = true)
  val disabledConfig = AuditingConfig(consumer = Some(consumer), enabled = false)
  implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId(UUID.randomUUID().toString)))

  val mockAuditorImpl: uk.gov.hmrc.audit.connector.AuditConnector = mock[uk.gov.hmrc.audit.connector.AuditConnector]

  def mockConnector(config: AuditingConfig): AuditConnector = new AuditConnector {
    override def auditingConfig: AuditingConfig = config
    override def auditorImpl: uk.gov.hmrc.audit.connector.AuditConnector = mockAuditorImpl
  }

  "sendEvent(MergedDataEvent)" should {
    "pass MergedDataEvent to wrapped connector" in {
      val event = mergedEvent("/some/uri")
      when(mockAuditorImpl.sendEvent(same(event))(anyObject())).thenReturn(Future(Success))
      mockConnector(enabledConfig).sendEvent(event).futureValue mustBe Success
    }

    "return Success if auditing is not enabled" in {
      val event = mergedEvent("/some/uri")
      mockConnector(disabledConfig).sendEvent(event).futureValue mustBe Success
      verifyZeroInteractions(mockAuditorImpl)
    }

    "return Success if audit event is for path /ping/ping" in {
      val event = mergedEvent("/ping/ping")
      mockConnector(disabledConfig).sendEvent(event).futureValue mustBe Success
      verifyZeroInteractions(mockAuditorImpl)
    }
  }

  "sendEvent(DataEvent)" should {
    "pass DataEvent to wrapped connector" in {
      val event = dataEvent("/some/uri")
      when(mockAuditorImpl.sendEvent(same(event))(anyObject())).thenReturn(Future(Success))
      mockConnector(enabledConfig).sendEvent(event).futureValue mustBe Success
    }

    "return Success if auditing is not enabled" in {
      val event = dataEvent("/some/uri")
      mockConnector(disabledConfig).sendEvent(event).futureValue mustBe Success
      verifyZeroInteractions(mockAuditorImpl)
    }

    "return Success if audit event is for path /ping/ping" in {
      val event = dataEvent("/ping/ping")
      mockConnector(disabledConfig).sendEvent(event).futureValue mustBe Success
      verifyZeroInteractions(mockAuditorImpl)
    }
  }

  "sendEvent(AuditEvent)" should {
    "pass AuditEvent to wrapped connector" in {
      val event = auditEvent("/some/uri")
      when(mockAuditorImpl.sendEvent(same(event))(anyObject())).thenReturn(Future(Success))
      mockConnector(enabledConfig).sendEvent(event).futureValue mustBe Success
    }

    "return Success if auditing is not enabled" in {
      val event = auditEvent("/some/uri")
      mockConnector(disabledConfig).sendEvent(event).futureValue mustBe Success
      verifyZeroInteractions(mockAuditorImpl)
    }

    "return Success if audit event is for path /ping/ping" in {
      val event = auditEvent("/ping/ping")
      mockConnector(disabledConfig).sendEvent(event).futureValue mustBe Success
      verifyZeroInteractions(mockAuditorImpl)
    }
  }

  def mergedEvent(path: String): MergedDataEvent = {
    MergedDataEvent("auditSource", "auditType", "eventId",
      DataCall(Map(EventKeys.Path -> path), Map.empty, DateTime.now(DateTimeZone.UTC)),
      DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)))
  }

  def dataEvent(path: String): DataEvent = {
    DataEvent("auditSource", "auditType", "eventId", Map(EventKeys.Path -> path))
  }

  def auditEvent(path: String)(implicit hc: HeaderCarrier): AuditEvent = {
    AuditEvent("auditSource", "auditType", "GET", path, DateTime.now(DateTimeZone.UTC))
  }
}
