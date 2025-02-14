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

package uk.gov.hmrc.play.audit.http.connector

import java.time.Instant
import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.{verify, verifyNoMoreInteractions, when}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import uk.gov.hmrc.audit.{DatastreamMetricsMock, HandlerResult}
import uk.gov.hmrc.http.{HeaderCarrier, SessionId}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.http.connector.AuditResult._
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}

import scala.concurrent.{ExecutionContext, Future}

case class MyExampleAudit(userType: String, vrn: String)

class AuditConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar
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

      val mergedEvent = MergedDataEvent(
        auditSource = "Test",
        auditType   = "Test",
        eventId     = "TestEventId",
        request     = DataCall(Map.empty, Map.empty, Instant.now()),
        response    = DataCall(Map.empty, Map.empty, Instant.now())
      )

      createConnector(enabledConfig).sendMergedEvent(mergedEvent).futureValue shouldBe Success

      verify(mockAuditChannel).send(any[String], any[JsValue])(any[ExecutionContext])
    }

    "add auditProvider if specified" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val mergedEventWithProvider = MergedDataEvent(
        auditProvider = Some("provider"),
        auditSource   = "source",
        auditType     = "type",
        eventId       = "TestEventId",
        request       = DataCall(Map.empty, Map.empty, Instant.now()),
        response      = DataCall(Map.empty, Map.empty, Instant.now())
      )

      createConnector(enabledConfig).sendMergedEvent(mergedEventWithProvider).futureValue shouldBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditProvider").as[String] shouldBe "provider"
      (captor.getValue \ "auditSource"  ).as[String] shouldBe "source"
      (captor.getValue \ "auditType"    ).as[String] shouldBe "type"
    }
  }

  "sendEvent" should {
    val event             = DataEvent(auditSource = "source", auditType = "type")
    val eventWithProvider = DataEvent(auditProvider = Some("provider"), auditSource = "source", auditType = "type")

    "call AuditChannel.send with the event converted to json" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      createConnector(enabledConfig).sendEvent(event).futureValue shouldBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditProvider").isDefined  shouldBe false
      (captor.getValue \ "auditSource"  ).as[String] shouldBe "source"
      (captor.getValue \ "auditType"    ).as[String] shouldBe "type"
    }

    "add auditProvider if specified" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      createConnector(enabledConfig).sendEvent(eventWithProvider).futureValue shouldBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditProvider").as[String] shouldBe "provider"
      (captor.getValue \ "auditSource"  ).as[String] shouldBe "source"
      (captor.getValue \ "auditType"    ).as[String] shouldBe "type"
    }

    "add tags if not specified" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))
      val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")))

      createConnector(enabledConfig).sendEvent(event)(headerCarrier, ec).futureValue shouldBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "tags" \ "X-Session-ID").as[String] shouldBe "session-123"
    }

    "return Disabled if auditing is not enabled" in {
      val disabledConfig = AuditingConfig(
        consumer    = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))),
        enabled     = false,
        auditSource = "the-project-name",
        auditSentHeaders = false
      )

      createConnector(disabledConfig).sendEvent(event).futureValue shouldBe AuditResult.Disabled

      verifyNoMoreInteractions(mockAuditChannel)
    }
  }

  "sendExtendedEvent" should {
    "call AuditChannel.send with extended event data converted to json" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val detail = Json.parse( """{"some-event": "value", "some-other-event": "other-value"}""")
      val event: ExtendedDataEvent = ExtendedDataEvent(auditSource = "source", auditType = "type", detail = detail)

      createConnector(enabledConfig).sendExtendedEvent(event).futureValue shouldBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditProvider"          ).isDefined  shouldBe false
      (captor.getValue \ "auditSource"            ).as[String] shouldBe "source"
      (captor.getValue \ "auditType"              ).as[String] shouldBe "type"
      (captor.getValue \ "metadata" \ "metricsKey").as[String] shouldBe "play.the-project-name"
    }

    "add auditProvider if specified" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val detail = Json.parse( """{"some-event": "value", "some-other-event": "other-value"}""")
      val event: ExtendedDataEvent = ExtendedDataEvent(auditSource = "source", auditType = "type", auditProvider = Some("provider"), detail = detail)

      createConnector(enabledConfig).sendExtendedEvent(event).futureValue shouldBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditProvider"          ).as[String] shouldBe "provider"
      (captor.getValue \ "auditSource"            ).as[String] shouldBe "source"
      (captor.getValue \ "auditType"              ).as[String] shouldBe "type"
      (captor.getValue \ "metadata" \ "metricsKey").as[String] shouldBe "play.the-project-name"
    }

    "sendExplicitEvent Map[String,String]" should {
      "call AuditChannel.send with tags read from headerCarrier" in {
        when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
          .thenReturn(Future.successful(HandlerResult.Success))

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        createConnector(enabledConfig).sendExplicitAudit("theAuditType", Map("a" -> "1"))(headerCarrier, ec)

        val captor = ArgumentCaptor.forClass(classOf[JsValue])
        verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
        (captor.getValue \ "auditSource"            ).as[String]             shouldBe "the-project-name"
        (captor.getValue \ "tags" \ "X-Session-ID"  ).as[String]             shouldBe "session-123"
        (captor.getValue \ "tags" \ "path"          ).as[String]             shouldBe "/a/b/c"
        (captor.getValue \ "detail"                 ).as[Map[String,String]] shouldBe Map("a" -> "1")
        (captor.getValue \ "metadata" \ "metricsKey").as[String]             shouldBe "play.the-project-name"
      }
    }

    "sendExplicitEvent [T]" should {
      "call AuditChannel.send with tags read from headerCarrier and serialize T" in {
        when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
          .thenReturn(Future.successful(HandlerResult.Success))
        val writes = Json.writes[MyExampleAudit]

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        createConnector(enabledConfig).sendExplicitAudit("theAuditType", MyExampleAudit("Agent","123"))(headerCarrier, ec, writes)

        val captor = ArgumentCaptor.forClass(classOf[JsValue])
        verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
        (captor.getValue \ "auditSource"            ).as[String] shouldBe "the-project-name"
        (captor.getValue \ "tags" \ "X-Session-ID"  ).as[String] shouldBe "session-123"
        (captor.getValue \ "tags" \ "path"          ).as[String] shouldBe "/a/b/c"
        (captor.getValue \ "detail" \ "userType"    ).as[String] shouldBe "Agent"
        (captor.getValue \ "detail" \ "vrn"         ).as[String] shouldBe "123"
        (captor.getValue \ "metadata" \ "metricsKey").as[String] shouldBe "play.the-project-name"
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

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditSource"            ).as[String]   shouldBe "the-project-name"
      (captor.getValue \ "tags" \ "X-Session-ID"  ).as[String]   shouldBe "session-123"
      (captor.getValue \ "tags" \ "path"          ).as[String]   shouldBe "/a/b/c"
      (captor.getValue \ "detail"                 ).as[JsObject] shouldBe expectedDetail
      (captor.getValue \ "metadata" \ "metricsKey").as[String]   shouldBe "play.the-project-name"
    }

    "add auditProvider if specified" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      createConnector(enabledConfig.copy(auditProvider=Some("theAuditProvider"))).sendExplicitAudit("theAuditType", expectedDetail)(headerCarrier, ec)

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditProvider").as[String] shouldBe "theAuditProvider"
      (captor.getValue \ "auditSource"  ).as[String] shouldBe "the-project-name"
      (captor.getValue \ "auditType"    ).as[String] shouldBe "theAuditType"
    }
  }

  "send" should {
    val expectedDetail = Json.obj("Address" -> Json.obj("line1" -> "Road", "postCode" -> "123"))

    "provide metricsKey metadata if available" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      createConnector(enabledConfig, Some("play.the-project-name")).send("theAuditType", expectedDetail)(ec)

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "metadata" \ "metricsKey").as[String] shouldBe "play.the-project-name"
    }

    "provide null in metadata if metricsKey is not available" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      createConnector(enabledConfig, metricsKey = None).send("theAuditType", expectedDetail)(ec)

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "metadata" \ "metricsKey").as[JsValue] shouldBe JsNull
    }
  }
}
