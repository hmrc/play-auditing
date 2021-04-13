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

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.stream.Materializer
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.JsValue
import uk.gov.hmrc.audit.HandlerResult.Success
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any

import scala.concurrent.{ExecutionContext, Future}

class AuditCounterSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar
     with OneInstancePerTest {

  class Test {

    implicit val _ec: ExecutionContext = RunInlineExecutionContext
    implicit val _as: ActorSystem = ActorSystem()

    var messages = Seq.empty[JsValue]

    val stubAuditChannel = new AuditChannel {
      override def auditingConfig: AuditingConfig = ???

      override def materializer: Materializer = ???

      override def lifecycle: ApplicationLifecycle = ???

      override def send(path: String, event: JsValue)(implicit ec: ExecutionContext): Future[HandlerResult] = {
        messages = messages :+ event
        Future.successful(Success)
      }
    }

    var metrics = Map.empty[String,()=>Long]

    val stubAuditMetrics = new AuditCounterMetrics {
      override def registerMetric(name: String, read:()=>Long):Unit = {
        metrics = metrics + (name -> read)
      }
    }

    //This does not work because CoordinatedShutdown is a final class
    val shutdownTaskCaptor = ArgumentCaptor.forClass(classOf[() => Future[Done]])
    val stubCoordinatedShutdown = mock[CoordinatedShutdown]
    verify(stubCoordinatedShutdown).addTask(any[String], any[String])(shutdownTaskCaptor.capture())

    def createCounter(): AuditCounter = {
      new AuditCounter {
        override def actorSystem: ActorSystem = _as

        override def auditingConfig: AuditingConfig = AuditingConfig(None, true, "projectname", false)

        def coordinatedShutdown : CoordinatedShutdown = stubCoordinatedShutdown

        override def ec: ExecutionContext = _ec

        override def auditChannel: AuditChannel = stubAuditChannel

        override def auditMetrics: AuditCounterMetrics = stubAuditMetrics
      }
    }

  }

  "AuditCounter" should {
    "increment the sequence" in new Test {
      val counter = createCounter()

      val first = counter.createMetadata()
      (first \ "metadata" \ "sequence").as[Long] mustBe 1

      val second = counter.createMetadata()
      (second \ "metadata" \ "sequence").as[Long] mustBe 2
      (first \ "metadata" \ "instanceID") mustEqual (second \ "metdata" \ "instanceID" )
    }

    "uniqueness of audit counter Id" in new Test {
      val counter1 = createCounter()
      val counter2 = createCounter()

      val metadata1 = counter1.createMetadata()
      val metadata2 = counter2.createMetadata()

      (metadata1 \ "metadata" \ "instanceID") mustNot be (metadata2 \ "metadata" \ "instanceID")
    }

//    "emit the counter on shutdown" in new Test {
//      val counter = createCounter()
//
//      (1 to 10).map(_ =>  counter.createMetadata())
//      shutdownTaskCaptor.getValue()
//
//      messages.length mustBe 1
//      (messages(0) \ "type").as[String] mustBe "audit-counter"
//      (messages(0) \ "sequence").as[Long] mustBe 10L
//      (messages(0) \ "isFinal").as[Boolean] mustBe true
//    }

    "record the counters as metrics" in new Test {
      val counter = createCounter()

      (1 to 10).map(_ =>  counter.createMetadata())
      metrics("audit-counter.sequence")() mustBe 10
      (1 to 10).map(_ =>  counter.createMetadata())
      metrics("audit-counter.sequence")() mustBe 20

//      shutdownTaskCaptor.getValue()
//      metrics("audit-counter.final")() mustBe 20
    }
  }
}
