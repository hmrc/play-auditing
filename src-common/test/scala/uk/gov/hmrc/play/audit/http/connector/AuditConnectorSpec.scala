/*
 * Copyright 2022 HM Revenue & Customs
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

import java.time.Instant
import akka.actor.ActorSystem
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.mockito.captor.ArgCaptor
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json.{JsNull, JsObject, JsString, JsValue, Json}
import uk.gov.hmrc.audit.{DatastreamMetricsMock, HandlerResult}
import uk.gov.hmrc.http.{HeaderCarrier, SessionId}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.http.connector.AuditResult._
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}

import scala.concurrent.{ExecutionContext, Future}

case class MyExampleAudit(userType: String, vrn: String)

class AuditConnectorSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar
     with ArgumentMatchersSugar
     with OneInstancePerTest
     with DatastreamMetricsMock {

  implicit val ec: ExecutionContext = RunInlineExecutionContext
  implicit val as: ActorSystem      = ActorSystem()

  private val consumer = Consumer(BaseUri("datastream-base-url", 8080, "http"))

  private val enabledConfig = AuditingConfig(
    consumer         = Some(consumer),
    enabled          = true,
    auditSource      = "the-project-name",
    auditSentHeaders = false
  )
  private val mockAuditChannel: AuditChannel = mock[AuditChannel]

  private def createConnector(config: AuditingConfig, metricsKey: Option[String] = Some("play.the-project-name")): AuditConnector =
    new AuditConnector {
      override def auditingConfig    = config
      override def auditChannel      = mockAuditChannel
      override def datastreamMetrics = mockDatastreamMetrics(metricsKey)
    }

  "sendMergedEvent" should {
    "call merged Datastream with event converted to json" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val mergedEvent = MergedDataEvent("Test", "Test", "TestEventId",
          DataCall(Map.empty, Map.empty, Instant.now()),
          DataCall(Map.empty, Map.empty, Instant.now()))

      createConnector(enabledConfig).sendMergedEvent(mergedEvent).futureValue mustBe Success

      verify(mockAuditChannel).send(any[String], any[JsValue])(any[ExecutionContext])
    }
  }

  "sendEvent" should {
    val event = DataEvent("source", "type")

    "call AuditChannel.send with the event converted to json" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))
      createConnector(enabledConfig).sendEvent(event).futureValue mustBe AuditResult.Success

      verify(mockAuditChannel).send(any[String], any[JsValue])(any[ExecutionContext])
    }

    "add tags if not specified" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))
      val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")))

      createConnector(enabledConfig).sendEvent(event)(headerCarrier, ec).futureValue mustBe AuditResult.Success

      val captor = ArgCaptor[JsValue]
      verify(mockAuditChannel).send(any[String], captor)(any[ExecutionContext])
      (captor.value \ "tags" \ "X-Session-ID").as[String] mustBe "session-123"
    }

    "return Disabled if auditing is not enabled" in {
      val disabledConfig = AuditingConfig(
        consumer    = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))),
        enabled     = false,
        auditSource = "the-project-name",
        auditSentHeaders = false
      )

      createConnector(disabledConfig).sendEvent(event).futureValue must be(AuditResult.Disabled)

      verifyNoMoreInteractions(mockAuditChannel)
    }
  }

  "sendExtendedEvent" should {
    "call AuditChannel.send with extended event data converted to json" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val detail = Json.parse( """{"some-event": "value", "some-other-event": "other-value"}""")
      val event: ExtendedDataEvent = ExtendedDataEvent(auditSource = "source", auditType = "type", detail = detail)

      createConnector(enabledConfig).sendExtendedEvent(event).futureValue mustBe AuditResult.Success

      val captor = ArgCaptor[JsValue]
      verify(mockAuditChannel).send(any[String], captor)(any[ExecutionContext])
      (captor.value \ "metadata" \ "metricsKey").as[JsString].value mustBe "play.the-project-name"
    }

    "sendExplicitEvent Map[String,String]" should {
      "call AuditChannel.send with tags read from headerCarrier" in {
        when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
          .thenReturn(Future.successful(HandlerResult.Success))

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        createConnector(enabledConfig).sendExplicitAudit("theAuditType", Map("a" -> "1"))(headerCarrier, ec)

        val captor = ArgCaptor[JsValue]
        verify(mockAuditChannel).send(any[String], captor)(any[ExecutionContext])
        (captor.value \ "auditSource"            ).as[String]             mustBe "the-project-name"
        (captor.value \ "tags" \ "X-Session-ID"  ).as[String]             mustBe "session-123"
        (captor.value \ "tags" \ "path"          ).as[String]             mustBe "/a/b/c"
        (captor.value \ "detail"                 ).as[Map[String,String]] mustBe Map("a" -> "1")
        (captor.value \ "metadata" \ "metricsKey").as[JsString].value     mustBe "play.the-project-name"
      }
    }

    "sendExplicitEvent [T]" should {
      "call AuditChannel.send with tags read from headerCarrier and serialize T" in {
        when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
          .thenReturn(Future.successful(HandlerResult.Success))
        val writes = Json.writes[MyExampleAudit]

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        createConnector(enabledConfig).sendExplicitAudit("theAuditType", MyExampleAudit("Agent","123"))(headerCarrier, ec, writes)

        val captor = ArgCaptor[JsValue]
        verify(mockAuditChannel).send(any[String], captor)(any[ExecutionContext])
        (captor.value \ "auditSource"            ).as[String] mustBe "the-project-name"
        (captor.value \ "tags" \ "X-Session-ID"  ).as[String] mustBe "session-123"
        (captor.value \ "tags" \ "path"          ).as[String] mustBe "/a/b/c"
        (captor.value \ "detail" \ "userType"    ).as[String] mustBe "Agent"
        (captor.value \ "detail" \ "vrn"         ).as[String] mustBe "123"
        (captor.value \ "metadata" \ "metricsKey").as[String] mustBe "play.the-project-name"
      }
    }
  }

  "sendExplicitEvent JsObject" should {
    val expectedDetail = Json.obj("Address" -> Json.obj("line1" -> "Road", "postCode" -> "123"))
    val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))

    "call AuditChannel.send with tags read from headerCarrier and pass through detail" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      createConnector(enabledConfig).sendExplicitAudit("theAuditType", expectedDetail)(headerCarrier, ec)

      val captor = ArgCaptor[JsValue]
      verify(mockAuditChannel).send(any[String], captor)(any[ExecutionContext])
      (captor.value \ "auditSource"            ).as[String]   mustBe "the-project-name"
      (captor.value \ "tags" \ "X-Session-ID"  ).as[String]   mustBe "session-123"
      (captor.value \ "tags" \ "path"          ).as[String]   mustBe "/a/b/c"
      (captor.value \ "detail"                 ).as[JsObject] mustBe expectedDetail
      (captor.value \ "metadata" \ "metricsKey").as[String]   mustBe "play.the-project-name"
    }
  }

  "send" should {
    val expectedDetail = Json.obj("Address" -> Json.obj("line1" -> "Road", "postCode" -> "123"))

    "provide metricsKey metadata if available" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      createConnector(enabledConfig, Some("play.the-project-name")).send("theAuditType", expectedDetail)(ec)

      val captor = ArgCaptor[JsValue]
      verify(mockAuditChannel).send(any[String], captor)(any[ExecutionContext])
      (captor.value \ "metadata" \ "metricsKey").as[JsString].value mustBe "play.the-project-name"
    }

    "provide null in metadata if metricsKey is not available" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      createConnector(enabledConfig, metricsKey = None).send("theAuditType", expectedDetail)(ec)

      val captor = ArgCaptor[JsValue]
      verify(mockAuditChannel).send(any[String], captor)(any[ExecutionContext])
      (captor.value \ "metadata" \ "metricsKey").as[JsValue] mustBe JsNull
    }
  }
}
