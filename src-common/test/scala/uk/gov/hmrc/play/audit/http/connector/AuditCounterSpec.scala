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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, verifyNoMoreInteractions, verifyZeroInteractions, when}
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.play.audit.http.config.AuditingConfig

import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

class AuditCounterSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar
     with OneInstancePerTest {


  val time = LocalDateTime.of(2021, 2, 2, 12, 0, 0).toInstant(ZoneOffset.of("Z"))

  class Test {

    implicit val executionContext = RunInlineExecutionContext

    val stubAuditChannel = mock[AuditChannel]
    val stubLogger = mock[Logger]
    val stubAuditCountPublisher = mock[AuditCountScheduler]

    var metrics = Map.empty[String,()=>Long]
    val stubAuditMetrics = new AuditCounterMetrics {
      override def registerMetric(name: String, read:()=>Long):Unit = {
        metrics = metrics + (name -> read)
      }
    }

    def createCounter(enabled: Boolean = true): AuditCounter = {
      new AuditCounter {
        override def auditingConfig = AuditingConfig(None, enabled, "projectname", false)
        override def auditChannel = stubAuditChannel
        override def auditMetrics = stubAuditMetrics
        override val logger = stubLogger
      }
    }
  }

  "AuditCounter" should {
    // TODO:  This should be service global - where's the right place to ensure this?
    "create a unique instanceId" in new Test {
      val counter1 = createCounter()
      val counter2 = createCounter()

      val metadata1 = counter1.createMetadata()
      val metadata2 = counter2.createMetadata()

      (metadata1 \ "metadata" \ "instanceID") mustNot be (metadata2 \ "metadata" \ "instanceID")
    }
  }

  "createMetadata" should {
    "increment the sequence" in new Test {
      val counter = createCounter()

      val first = counter.createMetadata()
      val second = counter.createMetadata()

      (first \ "metadata" \ "sequence").as[Long] mustBe 1
      (second \ "metadata" \ "sequence").as[Long] mustBe 2
    }

    "include the same instanceID" in new Test {
      val counter = createCounter()

      val first = counter.createMetadata()
      val second = counter.createMetadata()

      (first \ "metadata" \ "instanceID") mustEqual (second \ "metadata" \ "instanceID")
    }

    "include a validly formatted sendAttemptAt time" in new Test {

      val counter = new AuditCounter {
        override def auditingConfig = AuditingConfig(None, true, "projectname", false)
        override def auditChannel = stubAuditChannel
        override def auditMetrics = stubAuditMetrics
        override def currentTime() = time
      }

      val metadata = counter.createMetadata()
      (metadata \ "metadata" \ "sendAttemptAt").as[String] mustBe "2021-02-02T12:00:00.000+0000"
    }

    "record the counters as metrics" in new Test {
      val counter = createCounter()

      (1 to 10).map(_ =>  counter.createMetadata())
      metrics("audit-count.sequence")() mustBe 10
      (1 to 10).map(_ =>  counter.createMetadata())
      metrics("audit-count.sequence")() mustBe 20
    }

    "not record the counters as metrics if auditing is disabled" in new Test {
      val counter = createCounter(enabled=false)

      (1 to 10).map(_ =>  counter.createMetadata())
      metrics.isEmpty mustBe true
    }

    "warn if a final audit event has already been sent" in new Test {
      val counter = createCounter()

      when(stubAuditChannel.send(any(), any())(any())).thenReturn(Future.successful(HandlerResult.Success))
      counter.publish(isFinal=true)
      counter.createMetadata()

      verify(stubLogger).warn("Audit created after publication of final audit-count. This can lead to undetected audit loss.")
    }

  }

  "publish" should {
    "not publish if auditing is disabled" in new Test {
      val counter = createCounter(enabled = false)

      counter.publish(isFinal = false)
      counter.publish(isFinal = true)

      verifyNoMoreInteractions(stubAuditChannel)
      verifyNoMoreInteractions(stubLogger)
    }

    "publish the current count via the auditing channel and log, including relevant metadata" in new Test {
      val config = AuditingConfig(None, true, "projectname", false)
      val counter =  new AuditCounter {
        override def auditingConfig = config
        override def auditChannel = stubAuditChannel
        override def auditMetrics = stubAuditMetrics
        override val logger = stubLogger
        override def currentTime = time
        override val instanceID = "some-instance-id"
      }

      when(stubAuditChannel.send(any(), any())(any())).thenReturn(Future.successful(HandlerResult.Success))

      for (i <- 1 to 10) counter.createMetadata()
      counter.publish(isFinal = false)

      val expectedData = Json.obj(
        "type" -> "audit-count",
        "auditSource" -> config.auditSource,
        "instanceID" -> "some-instance-id",
        "timestamp" -> "2021-02-02T12:00:00.000+0000",
        "sequence" -> 10,
        "isFinal" -> false
      )

      verify(stubAuditChannel).send("/write/audit", expectedData)
      verify(stubLogger).info(s"AuditCount: $expectedData")
    }

    "record that non-final publishes are non-final" in new Test {
      val counter = createCounter()

      val captor: ArgumentCaptor[JsValue] = ArgumentCaptor.forClass(classOf[JsValue])

      when(stubAuditChannel.send(any(), any())(any())).thenReturn(Future.successful(HandlerResult.Success))

      counter.publish(isFinal = false)
      verify(stubAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])

      (captor.getValue \ "isFinal").as[Boolean] mustBe false
    }

    "record that final publishes are final" in new Test {
      val counter = createCounter()

      val captor: ArgumentCaptor[JsValue] = ArgumentCaptor.forClass(classOf[JsValue])

      when(stubAuditChannel.send(any(), any())(any())).thenReturn(Future.successful(HandlerResult.Success))

      counter.publish(isFinal = true)
      verify(stubAuditChannel).send(any[String], captor.capture())(any[ExecutionContext])

      (captor.getValue \ "isFinal").as[Boolean] mustBe true
    }

    "should update the published counter to the current count" in new Test {
      val counter = createCounter()
      when(stubAuditChannel.send(any(), any())(any())).thenReturn(Future.successful(HandlerResult.Success))

      for (i <- 1 to 10) counter.createMetadata()

      counter.publishedSequence.get mustBe 0
      counter.sequence.get mustBe 10

      counter.publish(isFinal = false)

      counter.publishedSequence.get mustBe 10
      counter.sequence.get mustBe 10
    }
  }
}
