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

package uk.gov.hmrc.play.audit.model

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import uk.gov.hmrc.audit.DatastreamMetricsMock
import uk.gov.hmrc.http.HeaderNames._
import uk.gov.hmrc.http.{HeaderCarrier, RequestId}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.http.connector.{AuditChannel, AuditConnector, DatastreamMetrics}
import uk.gov.hmrc.play.audit.model.Audit.OutputTransformer

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class AuditSpec extends AnyWordSpecLike with Matchers with Eventually with DatastreamMetricsMock {

  class MockAudit(appName: String, connector: AuditConnector) extends Audit(appName, connector) {

    var capturedDataEvent: DataEvent = _

    override def sendDataEvent: (DataEvent) => Unit = capture

    def capture(de: DataEvent): Unit = {
      this.capturedDataEvent = de
    }

    def verifyDataEvent(expected: DataEvent): Unit = {
      this.capturedDataEvent.auditSource shouldBe expected.auditSource
      this.capturedDataEvent.auditType   shouldBe expected.auditType
      this.capturedDataEvent.tags        shouldBe expected.tags
      this.capturedDataEvent.detail      shouldBe expected.detail
    }
  }

  case class AuditableEvent(transaction: String, event: String)

  val transactionName = "transaction-name"
  val inputs = Map("body" -> "request body as a string")
  val transformer: OutputTransformer[AuditableEvent] =
    (auditable: AuditableEvent) =>
      TransactionSuccess("transactionId" -> auditable.transaction, "event" -> auditable.event)

  val exampleRequestId = "12345"
  implicit val hc: HeaderCarrier = HeaderCarrier(requestId = Some(RequestId(exampleRequestId)))

  val auditConnector: AuditConnector = {
    val testconfig = AuditingConfig(
      consumer         = Some(Consumer(BaseUri("localhost", 11111, "http"))),
      enabled          = true,
      auditSource      = "the-project-name",
      auditSentHeaders = false
    )
    implicit val system = ActorSystem()
    val datastreamMetricsMock = mockDatastreamMetrics(Some("play.the-project-name"))

    new AuditConnector {
      override def auditingConfig = testconfig

      override def datastreamMetrics = datastreamMetricsMock

      override def auditChannel = new AuditChannel {
        override def auditingConfig   : AuditingConfig       = testconfig
        override def materializer     : Materializer         = implicitly
        override def lifecycle        : ApplicationLifecycle = new DefaultApplicationLifecycle()
        override def datastreamMetrics: DatastreamMetrics    = datastreamMetricsMock
      }
    }
  }

  "An Audit object" should {
    "be represented as an DataEvent when only passed an input" in {
      val appName = "app-name-input-as-STRING"
      val inputSuffixKey = ""
      val auditable = AuditableEvent("txId1", "an event to log")
      val inputs = Map(s"input$inputSuffixKey" -> "request body no key provided")
      val outputs = Map("output-transactionId" -> auditable.transaction, "output-event" -> auditable.event)

      val audit = new MockAudit(appName, auditConnector)

      audit.as[AuditableEvent]((transactionName, "request body no key provided", transformer)) { () => auditable}

      audit.verifyDataEvent(
        DataEvent(
          auditSource = appName,
          auditType   = EventTypes.Succeeded,
          tags        = Map(xRequestId -> exampleRequestId, "transactionName" -> transactionName),
          detail      = inputs ++ outputs)
        )
    }

    "be represented as an DataEvent when passed inputs map" in {
      val appName = "app-name-input-as-MAP"
      val inputSuffixKey = "-event-key"
      val auditable = AuditableEvent("txId1", "an event to log")
      val inputs = Map(s"input$inputSuffixKey" -> "request body no key provided")
      val outputs = Map("output-transactionId" -> auditable.transaction, "output-event" -> auditable.event)

      val audit = new MockAudit(appName, auditConnector)

      audit.as[AuditableEvent]((transactionName, Map("event-key" -> "request body no key provided"), transformer)) { () => auditable}

      audit.verifyDataEvent(
        DataEvent(
          auditSource = appName,
          auditType   = EventTypes.Succeeded,
          tags        = Map(xRequestId -> exampleRequestId, "transactionName" -> transactionName),
          detail      = inputs ++ outputs
        )
      )
    }

    "detail failure as an DataEvent" in {
      val appName = "app-name"
      val inputSuffixKey = ""
      val auditable = AuditableEvent("txId1", "an event to log")
      val inputs = Map(s"input$inputSuffixKey" -> "request body no key provided")
      val failureReason: String = "Some error while mapping body result to transaction result"
      val outputs = Map("transactionFailureReason" -> s"Exception Generated: $failureReason")

      val audit = new MockAudit(appName, auditConnector)
      val failingTransformer: OutputTransformer[AuditableEvent] = (_: AuditableEvent) => {
        throw new RuntimeException(failureReason)
      }
      audit.as[AuditableEvent]((transactionName, "request body no key provided", failingTransformer)) { () => auditable}

      eventually {
        audit.verifyDataEvent(
          DataEvent(
            auditSource = appName,
            auditType   = EventTypes.Failed,
            tags        = Map(xRequestId -> exampleRequestId, "transactionName" -> transactionName),
            detail      = inputs ++ outputs
          )
        )
      }
    }
  }

  "auditing an event generated by an AsyncBody" should {

    "generate a DataEvent of type success if the body was successfully executed" in {
      val appName = "app-name"
      val inputSuffixKey = ""
      val auditable = AuditableEvent("txId1", "an event to log")
      val inputs = Map(s"input$inputSuffixKey" -> "request body no key provided")
      val outputs = Map("output-transactionId" -> auditable.transaction, "output-event" -> auditable.event)

      val audit = new MockAudit(appName, auditConnector)

      Await.result(
        audit.asyncAs[AuditableEvent]((transactionName, "request body no key provided", transformer)) {
          () => Future.successful(auditable)
        },
        5.seconds
      )

      eventually {
        audit.verifyDataEvent(
          DataEvent(
            auditSource = appName,
            auditType   = EventTypes.Succeeded,
            tags        = Map(xRequestId -> exampleRequestId, "transactionName" -> transactionName),
            detail      = inputs ++ outputs
          )
        )
      }
    }

    "generate a DataEvent of type failed if the body failed to be executed" in {
      val appName = "app-name"
      val inputSuffixKey = ""
      val inputs = Map(s"input$inputSuffixKey" -> "request body no key provided")
      val failureReason: String = "Some error while invoking body"
      val outputs = Map("transactionFailureReason" -> s"Exception Generated: $failureReason")

      val audit = new MockAudit(appName, auditConnector)

      audit.asyncAs[AuditableEvent]((transactionName, "request body no key provided", transformer)) { () => Future.failed(new RuntimeException(failureReason)) }

      eventually {
        audit.verifyDataEvent(
          DataEvent(
            auditSource = appName,
            auditType   = EventTypes.Failed,
            tags        = Map(xRequestId -> exampleRequestId, "transactionName" -> transactionName),
            detail      = inputs ++ outputs
          )
        )
      }
    }

    "generate a DataEvent of type failed if the body was successfully executed but the output transformer mapped the result into a failure" in {
      val appName = "app-name"
      val inputSuffixKey = ""
      val auditable = AuditableEvent("txId1", "an event to log")
      val inputs = Map(s"input$inputSuffixKey" -> "request body no key provided")
      val failureReason = "Some error while mapping body result to transaction result"
      val outputs = Map("transactionFailureReason" -> s"Exception Generated: $failureReason")

      val audit = new MockAudit(appName, auditConnector)
      val failingTransformer: OutputTransformer[AuditableEvent] = (_: AuditableEvent) => {
        throw new RuntimeException(failureReason)
      }
      audit.asyncAs[AuditableEvent]((transactionName, "request body no key provided", failingTransformer)) { () => Future.successful(auditable)}

      eventually {
        audit.verifyDataEvent(
          DataEvent(
            auditSource = appName,
            auditType   = EventTypes.Failed,
            tags        = Map(xRequestId -> exampleRequestId, "transactionName" -> transactionName),
            detail      = inputs ++ outputs
          )
        )
      }
    }

    "handle an exception thrown when the future body gets created" in {
      val appName = "app-name"
      val inputSuffixKey = ""
      val inputs = Map(s"input$inputSuffixKey" -> "request body no key provided")
      val failureReason: String = "Some error while instantiating body future"
      val outputs = Map("transactionFailureReason" -> s"Exception Generated: $failureReason")

      def throwException(): Future[AuditableEvent] = throw new RuntimeException(failureReason)

      val audit = new MockAudit(appName, auditConnector)
      audit.asyncAs[AuditableEvent]((transactionName, "request body no key provided", transformer)) { () => throwException() }

      eventually {
        audit.verifyDataEvent(DataEvent(
          auditSource = appName,
          auditType   = EventTypes.Failed,
          tags        = Map(xRequestId -> exampleRequestId, "transactionName" -> transactionName),
          detail      = inputs ++ outputs
          )
        )
      }
    }
  }
}
