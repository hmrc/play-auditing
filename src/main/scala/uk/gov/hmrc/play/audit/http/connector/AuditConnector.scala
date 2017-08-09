/*
 * Copyright 2017 HM Revenue & Customs
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

import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.http.{CorePost, HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.model.{AuditEvent, MergedDataEvent}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

trait AuditEventFailureKeys {
  private val EventMissed = "DS_EventMissed"
  val LoggingAuditRequestFailureKey : String = EventMissed + "_AuditFailureResponse"
  val LoggingAuditFailureResponseKey : String = EventMissed + "_AuditRequestFailure"
}

object AuditEventFailureKeys extends AuditEventFailureKeys

sealed trait AuditResult
object AuditResult {
  case object Success extends AuditResult
  case object Disabled extends AuditResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with AuditResult
}

trait ResponseFormatter {
  import AuditEventFailureKeys._
  protected def checkResponse(body: JsValue, response: HttpResponse): Option[String] = {
    if (response.status >= 300) Some(s"$LoggingAuditFailureResponseKey : status code : ${response.status} : audit item : $body")
    else None
  }

  protected def makeFailureMessage(body: JsValue): String = s"$LoggingAuditRequestFailureKey : audit item : $body"
}

trait Auditor {
  def sendEvent(event: AuditEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult]
  def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult]
  def sendLargeMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult]
}

trait ConfigProvider {
  def auditingConfig: AuditingConfig
}

trait LoggerProvider {
  val logger: Logger

  protected def logError(s: String) = logger.warn(s)

  protected def logError(s: String, t: Throwable) = logger.warn(s, t)
}

trait ResultHandler extends ResponseFormatter {
  this: LoggerProvider =>
  protected def handleResult(resultF: Future[HttpResponse], body: JsValue)(implicit ec: ExecutionContext): Future[HttpResponse] = {
    resultF
      .recoverWith { case t =>
        val message = makeFailureMessage(body)
        logError(message, t)
        Future.failed(AuditResult.Failure(message, Some(t)))
      }
      .map { response =>
        checkResponse(body, response) match {
          case Some(error) =>
            logError(error)
            throw AuditResult.Failure(error)
          case None => response
        }
      }
  }
}

trait AuditorImpl extends Auditor with ResultHandler with CorePost  {
  this: ConfigProvider with LoggerProvider =>

  protected def callAuditConsumer(url:String, body: JsValue)(implicit hc: HeaderCarrier, ec : ExecutionContext): Future[HttpResponse] =
    POSTString(url, Json.stringify(body))

  def sendEvent(event: AuditEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] =
    sendEvent(auditingConfig.consumer.map(_.singleEventUrl), Json.toJson(event))

  def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] =
    sendEvent(auditingConfig.consumer.map(_.mergedEventUrl), Json.toJson(event))

  def sendLargeMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] =
    sendEvent(auditingConfig.consumer.map(_.largeMergedEventUrl), Json.toJson(event))

  private def sendEvent(urlOption: Option[String], body: JsValue)(implicit hc: HeaderCarrier, ec : ExecutionContext) = {
    if (auditingConfig.enabled) {
      val url = urlOption.getOrElse( throw new Exception("Missing event consumer URL") )
      handleResult(callAuditConsumer(url, body), body).map { _ => AuditResult.Success }
    } else {
      logger.info(s"auditing disabled for request-id ${hc.requestId}, session-id: ${hc.sessionId}")
      Future.successful(AuditResult.Disabled)
    }
  }
}

trait AuditConnector extends AuditorImpl with ConfigProvider with LoggerProvider {
  def auditingConfig: AuditingConfig
  override val logger: Logger = LoggerFactory.getLogger("auditing")
}
