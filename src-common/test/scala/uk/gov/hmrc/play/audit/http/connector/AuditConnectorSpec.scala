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

package uk.gov.hmrc.play.audit.http.connector

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.github.tomakehurst.wiremock.client.WireMock.{verify => _, _}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import uk.gov.hmrc.audit.HandlerResult
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
     with OneInstancePerTest {

  implicit val ec: ExecutionContext = RunInlineExecutionContext
  implicit val as: ActorSystem      = ActorSystem()
  implicit val m: Materializer      = ActorMaterializer()//required for play 2.6

  private val consumer = Consumer(BaseUri("datastream-base-url", 8080, "http"))
  private val enabledConfig = AuditingConfig(
    consumer = Some(consumer),
    enabled = true,
    auditSource = "the-project-name",
    auditSentHeaders = false,
    metricsKey = "play.the-project-name")

  private val mockAuditChannel: AuditChannel = mock[AuditChannel]

  private def createConnector(config: AuditingConfig): AuditConnector =
    new AuditConnector {
      override def auditingConfig = config
      override def auditChannel = mockAuditChannel
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

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      val tags = (captor.getValue \ "tags").as[JsObject]
      (tags \ "X-Session-ID").as[String] mustBe "session-123"
    }

    "return Disabled if auditing is not enabled" in {
      val disabledConfig = AuditingConfig(
        consumer    = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))),
        enabled     = false,
        auditSource = "the-project-name",
        auditSentHeaders = false,
        metricsKey = "play.the-project-name"
      )

      createConnector(disabledConfig).sendEvent(event).futureValue must be(AuditResult.Disabled)

      verifyNoInteractions(mockAuditChannel)
    }
  }

  "sendExtendedEvent" should {
    "call AuditChannel.send with extended event data converted to json" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val detail = Json.parse( """{"some-event": "value", "some-other-event": "other-value"}""")
      val event: ExtendedDataEvent = ExtendedDataEvent(auditSource = "source", auditType = "type", detail = detail)

      createConnector(enabledConfig).sendExtendedEvent(event).futureValue mustBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "metadata" \ "metricsKey").as[JsString].value mustBe "play.the-project-name"
    }

    "sendExplicitEvent Map[String,String]" should {
      "call AuditChannel.send with tags read from headerCarrier" in {
        when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
          .thenReturn(Future.successful(HandlerResult.Success))

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        createConnector(enabledConfig).sendExplicitAudit("theAuditType", Map("a" -> "1"))(headerCarrier, ec)

        val captor = ArgumentCaptor.forClass(classOf[JsValue])
        verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
        (captor.getValue \ "auditSource").as[String] mustBe "the-project-name"
        val tags = (captor.getValue \ "tags").as[JsObject]
        (tags \ "X-Session-ID").as[String] mustBe "session-123"
        (tags \ "path").as[String] mustBe "/a/b/c"
        (captor.getValue \ "detail").as[Map[String,String]] mustBe Map("a" -> "1")

        (captor.getValue \ "metadata" \ "metricsKey").as[JsString].value mustBe "play.the-project-name"
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
        (captor.getValue \ "auditSource").as[String] mustBe "the-project-name"
        val tags = (captor.getValue \ "tags").as[JsObject]
        (tags \ "X-Session-ID").as[String] mustBe "session-123"
        (tags \ "path").as[String] mustBe "/a/b/c"
        val detail = (captor.getValue \ "detail").as[JsObject]
        (detail \ "userType").as[String] mustBe "Agent"
        (detail \ "vrn").as[String] mustBe "123"

        (captor.getValue \ "metadata" \ "metricsKey").as[JsString].value mustBe "play.the-project-name"
      }
    }
  }

  "sendExplicitEvent JsObject" should {
    "call AuditChannel.send with tags read from headerCarrier and pass through detail" in {
      when(mockAuditChannel.send(any[String], any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val expectedDetail = Json.obj("Address" -> Json.obj("line1" -> "Road", "postCode" -> "123"))
      val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
      createConnector(enabledConfig).sendExplicitAudit("theAuditType", expectedDetail)(headerCarrier, ec)

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditSource").as[String] mustBe "the-project-name"
      val tags = (captor.getValue \ "tags").as[JsObject]
      (tags \ "X-Session-ID").as[String] mustBe "session-123"
      (tags \ "path").as[String] mustBe "/a/b/c"
      val detail = (captor.getValue \ "detail").as[JsObject]
      detail mustBe expectedDetail

      (captor.getValue \ "metadata" \ "metricsKey").as[JsString].value mustBe "play.the-project-name"
    }
  }
}
