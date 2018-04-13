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

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.hmrc.audit.{HandlerResult, handler, serialiser}
import uk.gov.hmrc.audit.handler.{AuditHandler, DatastreamHandler, LoggingHandler}
import uk.gov.hmrc.audit.serialiser.{AuditSerialiser, AuditSerialiserLike}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.model.{DataEvent, ExtendedDataEvent, MergedDataEvent}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

sealed trait AuditResult
object AuditResult {
  case object Success extends AuditResult
  case object Disabled extends AuditResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with AuditResult

  def fromHandlerResult(result: HandlerResult): AuditResult = {
    result match {
      case HandlerResult.Success =>
        Success
      case HandlerResult.Rejected =>
        Failure("Event was actively rejected")
      case HandlerResult.Failure =>
        Failure("Event sending failed")
    }
  }
}

trait AuditConnector {
  def auditingConfig: AuditingConfig

  val defaultConnectionTimeout = 5000
  val defaultRequestTimeout = 5000
  val defaultBaseUri = BaseUri("datstream.protected.mdtp", 90, "http")

  lazy val consumer: Consumer = auditingConfig.consumer.getOrElse(Consumer(defaultBaseUri))
  lazy val baseUri: BaseUri = consumer.baseUri

  def simpleDatastreamHandler: AuditHandler = new DatastreamHandler(baseUri.protocol, baseUri.host,
    baseUri.port, s"/${consumer.singleEventUri}", defaultConnectionTimeout, defaultRequestTimeout)

  def mergedDatastreamHandler: AuditHandler = new DatastreamHandler(baseUri.protocol, baseUri.host,
    baseUri.port, s"/${consumer.mergedEventUri}", defaultConnectionTimeout, defaultRequestTimeout)

  def loggingConnector: AuditHandler = LoggingHandler
  def auditSerialiser: AuditSerialiserLike = AuditSerialiser

  private val log: Logger = LoggerFactory.getLogger(getClass)

  def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled(send, auditSerialiser.serialise(event), simpleDatastreamHandler)
  }

  def sendExtendedEvent(event: ExtendedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled(send, auditSerialiser.serialise(event), simpleDatastreamHandler)
  }

  def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled(send, auditSerialiser.serialise(event), mergedDatastreamHandler)
  }

  private def ifEnabled(func: (String, AuditHandler) => Future[HandlerResult], event: String, handler: AuditHandler)(implicit hc: HeaderCarrier, ec : ExecutionContext): Future[AuditResult] = {
    if (auditingConfig.enabled) {
      func.apply(event, handler).map(result => AuditResult.fromHandlerResult(result))
    } else {
      log.info(s"auditing disabled for request-id ${hc.requestId}, session-id: ${hc.sessionId}")
      Future.successful(AuditResult.Disabled)
    }
  }

  private def send(event: String, datastreamHandler: AuditHandler)(implicit ec: ExecutionContext): Future[HandlerResult] = Future {
    try {
      val result: HandlerResult = datastreamHandler.sendEvent(event)
      result match {
        case HandlerResult.Success =>
          result
        case HandlerResult.Rejected =>
          result
        case HandlerResult.Failure =>
          loggingConnector.sendEvent(event)
      }
    } catch {
      case e: Throwable =>
        log.error("Error in handler code", e)
        HandlerResult.Failure
    }
  }
}
