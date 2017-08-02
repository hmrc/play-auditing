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

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.hmrc.audit.connector.AuditorImpl
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.audit.model.AuditEvent
import uk.gov.hmrc.play.audit.model.{DataEvent, MergedDataEvent}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

sealed trait AuditResult
object AuditResult {
  case object Success extends AuditResult
  case object Disabled extends AuditResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with AuditResult

  def fromCoreResult(result: uk.gov.hmrc.audit.AuditResult): AuditResult = {
    result match {
      case uk.gov.hmrc.audit.AuditResult.Success =>
        Success
      case uk.gov.hmrc.audit.AuditResult.Rejected =>
        Failure("Event was actively rejected")
      case uk.gov.hmrc.audit.AuditResult.Failure =>
        Failure("Event sending failed")
    }
  }
}

trait ConfigProvider {
  def auditingConfig: AuditingConfig
}

trait AuditConnector extends AuditorImpl with ConfigProvider {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  def auditingConfig: AuditingConfig

  def sendEvent(event: AuditEvent)(implicit hc: HeaderCarrier, ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled[AuditEvent](super.sendEvent, event)
  }

  @deprecated
  def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled[DataEvent](super.sendEvent, event)
  }

  @deprecated
  def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier = HeaderCarrier(), ec : ExecutionContext): Future[AuditResult] = {
    ifEnabled[MergedDataEvent](super.sendMergedEvent, event)
  }

  def ifEnabled[T](func: Function[T, Future[uk.gov.hmrc.audit.AuditResult]], event: T)(implicit hc: HeaderCarrier, ec : ExecutionContext): Future[AuditResult] = {
    if (auditingConfig.enabled) {
      func.apply(event).map(result => AuditResult.fromCoreResult(result))
    } else {
      log.info(s"auditing disabled for request-id ${hc.requestId}, session-id: ${hc.sessionId}")
      Future.successful(AuditResult.Disabled)
    }
  }
}

object AuditConnector {
  def apply(config: AuditingConfig) = new AuditConnector { def auditingConfig: AuditingConfig = config }
}
