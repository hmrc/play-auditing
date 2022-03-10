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

import java.util.UUID

import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsObject, Json, Writes}
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.audit.serialiser.{AuditSerialiser, AuditSerialiserLike}
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.model.{DataEvent, ExtendedDataEvent, MergedDataEvent}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions._

import scala.concurrent.{ExecutionContext, Future}

sealed trait AuditResult
object AuditResult {
  case object Success extends AuditResult
  case object Disabled extends AuditResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with AuditResult

  def fromHandlerResult(result: HandlerResult): AuditResult =
    result match {
      case HandlerResult.Success  => Success
      case HandlerResult.Rejected => Failure("Event was actively rejected")
      case HandlerResult.Failure  => Failure("Event sending failed")
    }
}

trait AuditConnector {
  def auditingConfig: AuditingConfig
  def auditChannel  : AuditChannel
  def datastreamMetrics: DatastreamMetrics

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  lazy val auditSentHeaders: Boolean = auditingConfig.auditSentHeaders

  lazy val auditSerialiser: AuditSerialiserLike = AuditSerialiser

  def sendExplicitAudit(auditType: String, detail: Map[String, String])(implicit hc: HeaderCarrier, ec: ExecutionContext): Unit =
    sendExplicitAudit(auditType, Json.toJson(detail).as[JsObject])

  def sendExplicitAudit[T](auditType: String, detail: T)(implicit hc: HeaderCarrier, ec: ExecutionContext, writes: Writes[T]): Unit =
    sendExplicitAudit(auditType, Json.toJson(detail).as[JsObject])

  def sendExplicitAudit(auditType: String, detail: JsObject)(implicit hc: HeaderCarrier, ec: ExecutionContext): Unit =
    sendExtendedEvent(
      ExtendedDataEvent(
        auditSource = auditingConfig.auditSource,
        auditType   = auditType,
        eventId     = UUID.randomUUID().toString,
        tags        = hc.toAuditTags(),
        detail      = detail
      )
    )

  def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec: ExecutionContext): Future[AuditResult] =
    ifEnabled {
      send(
        "/write/audit",
        auditSerialiser.serialise(event.copy(tags = hc.appendToDefaultTags(event.tags)))
        )
    }

  def sendExtendedEvent(event: ExtendedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec: ExecutionContext): Future[AuditResult] =
    ifEnabled {
      send(
         "/write/audit",
        auditSerialiser.serialise(event.copy(tags = hc.appendToDefaultTags(event.tags)))
      )
    }

  def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec: ExecutionContext): Future[AuditResult] =
    ifEnabled {
      send(
        "/write/audit/merged",
        auditSerialiser.serialise(event)
      )
    }

  private def ifEnabled(send: => Future[HandlerResult])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AuditResult] =
    if (auditingConfig.enabled) {
      send.map(AuditResult.fromHandlerResult)
    } else {
      logger.info(s"auditing disabled for request-id ${hc.requestId}, session-id: ${hc.sessionId}")
      Future.successful(AuditResult.Disabled)
    }

  private[connector] def send(path:String, audit:JsObject)(implicit ec: ExecutionContext): Future[HandlerResult] = {
    val metadata = Json.obj("metadata" -> Json.obj("metricsKey" -> datastreamMetrics.metricsKey))
    auditChannel.send(path, audit ++ metadata)
  }
}
