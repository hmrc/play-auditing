/*
 * Copyright 2015 HM Revenue & Customs
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

package uk.gov.hmrc.play.audit.filters

import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.{Matchers, WordSpecLike}
import play.api.test.Helpers._
import play.api.test.{FakeApplication, FakeRequest}
import uk.gov.hmrc.play.audit.EventTypes
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult, MockAuditConnector}
import uk.gov.hmrc.play.audit.model.{DataEvent, AuditEvent}
import uk.gov.hmrc.play.http.HeaderCarrier
import uk.gov.hmrc.play.test.Concurrent.await

import uk.gov.hmrc.play.test.Http._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class AuditFilterSpec extends WordSpecLike with Matchers with Eventually with ScalaFutures with FilterFlowMock {

  "AuditFilter" should {
    val applicationName = "app-name"

    val xRequestId = "A_REQUEST_ID"
    val xSessionId = "A_SESSION_ID"
    val deviceID = "A_DEVICE_ID"

    implicit val hc = HeaderCarrier
    val request = FakeRequest().withHeaders("X-Request-ID" -> xRequestId, "X-Session-ID" -> xSessionId, "deviceID" -> deviceID)

    def createAuditConnector = new MockAuditConnector {
      var events: List[AuditEvent] = List.empty[AuditEvent]
      override def sendEvent(event: AuditEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext) = {
        events = events :+ event
        Future.successful(AuditResult.Success)
      }
    }

    def createAuditFilter(connector: MockAuditConnector) =
      new AuditFilter {
        override val auditConnector: AuditConnector = connector
        override val appName: String = applicationName

        override def controllerNeedsAuditing(controllerName: String): Boolean = true
      }

    "audit a request and response with header information" in running(FakeApplication()) {
      val mockAuditConnector = createAuditConnector
      val auditFilter = createAuditFilter(mockAuditConnector)

      val result = await(auditFilter.apply(nextAction)(request).run)
      await(enumerateResponseBody(result))

      eventually {
        val events = mockAuditConnector.events
        events should have size 1

        events(0).auditSource shouldBe applicationName
        events(0).auditType shouldBe EventTypes.RequestReceived
        events(0).tags("X-Request-ID") shouldBe xRequestId
        events(0).tags("X-Session-ID") shouldBe xSessionId
        events(0).asInstanceOf[DataEvent].detail("deviceID") shouldBe deviceID
      }
    }

    "audit a response even when an action futher down the chain throws an exception" in running(FakeApplication()) {
      val mockAuditConnector = createAuditConnector
      val auditFilter = createAuditFilter(mockAuditConnector)

      a[RuntimeException] should be thrownBy await(auditFilter.apply(exceptionThrowingAction)(request).run)

      eventually {
        val events = mockAuditConnector.events
        events should have size 1

        events(0).auditSource shouldBe applicationName
        events(0).auditType shouldBe EventTypes.RequestReceived
        events(0).tags("X-Request-ID") shouldBe xRequestId
        events(0).tags("X-Session-ID") shouldBe xSessionId
        events(0).asInstanceOf[DataEvent].detail("deviceID") shouldBe deviceID
      }
    }
  }
}
