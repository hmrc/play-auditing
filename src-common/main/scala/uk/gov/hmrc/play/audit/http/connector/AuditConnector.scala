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

import java.util.UUID

import akka.stream.Materializer
import org.slf4j.{Logger, LoggerFactory}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsValue, JsObject, Json, Writes}
import uk.gov.hmrc.audit.{HandlerResult, WSClient}
import uk.gov.hmrc.audit.handler.{AuditHandler, DatastreamHandler, LoggingHandler}
import uk.gov.hmrc.audit.serialiser.{AuditSerialiser, AuditSerialiserLike}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.model.{DataEvent, ExtendedDataEvent, MergedDataEvent}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions._

import scala.concurrent.duration.{Duration, DurationInt}
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
  def materializer  : Materializer
  def lifecycle     : ApplicationLifecycle

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  val defaultConnectionTimeout: Duration = 5000.millis
  val defaultRequestTimeout: Duration    = 5000.millis
  val defaultBaseUri: BaseUri            = BaseUri("datastream.protected.mdtp", 90, "http")

  lazy val consumer: Consumer = auditingConfig.consumer.getOrElse(Consumer(defaultBaseUri))
  lazy val baseUri: BaseUri = consumer.baseUri
  lazy val auditSentHeaders: Boolean = auditingConfig.auditSentHeaders

  private lazy val wsClient: WSClient = {
    implicit val m: Materializer = materializer
    val wsClient = WSClient(
      connectTimeout = defaultConnectionTimeout,
      requestTimeout = defaultRequestTimeout,
      userAgent      = auditingConfig.auditSource
    )
    lifecycle.addStopHook { () =>
      logger.info("Closing play-auditing http connections...")
      wsClient.close()
      Future.successful(())
    }
    wsClient
  }

  lazy val simpleDatastreamHandler: AuditHandler =
    new DatastreamHandler(
      baseUri.protocol,
      baseUri.host,
      baseUri.port,
      s"/${consumer.singleEventUri}",
      wsClient
    )

  lazy val mergedDatastreamHandler: AuditHandler =
    new DatastreamHandler(
      baseUri.protocol,
      baseUri.host,
      baseUri.port,
      s"/${consumer.mergedEventUri}",
      wsClient
    )

  lazy val loggingConnector: AuditHandler = LoggingHandler
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
      send(simpleDatastreamHandler){
        auditSerialiser.serialise(event.copy(tags = hc.appendToDefaultTags(event.tags)))
      }
    }

  def sendExtendedEvent(event: ExtendedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec: ExecutionContext): Future[AuditResult] =
    ifEnabled {
      send(simpleDatastreamHandler){
        auditSerialiser.serialise(event.copy(tags = hc.appendToDefaultTags(event.tags)))
      }
    }

  def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec: ExecutionContext): Future[AuditResult] =
    ifEnabled {
      send(mergedDatastreamHandler){
        auditSerialiser.serialise(event)
       }
    }

  private def ifEnabled(send: => Future[HandlerResult])(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AuditResult] =
    if (auditingConfig.enabled) {
      send.map(AuditResult.fromHandlerResult)
    } else {
      logger.info(s"auditing disabled for request-id ${hc.requestId}, session-id: ${hc.sessionId}")
      Future.successful(AuditResult.Disabled)
    }

  private def send(datastreamHandler: AuditHandler)(event: JsValue)(implicit ec: ExecutionContext): Future[HandlerResult] =
    datastreamHandler.sendEvent(event)
      .flatMap {
        case HandlerResult.Failure => loggingConnector.sendEvent(event)
        case result                => Future.successful(result)
      }
      .recover {
        case e: Throwable =>
          logger.error("Error in handler code", e)
          HandlerResult.Failure
      }
}
