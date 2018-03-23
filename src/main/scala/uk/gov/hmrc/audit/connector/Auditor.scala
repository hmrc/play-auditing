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

package uk.gov.hmrc.audit.connector

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.hmrc.audit.AuditResult
import uk.gov.hmrc.audit.handler.{AuditHandler, DatastreamHandler, LoggingHandler}
import uk.gov.hmrc.audit.model.AuditEvent
import uk.gov.hmrc.audit.serialiser.{AuditSerialiser, AuditSerialiserLike}
import uk.gov.hmrc.play.audit.model.{DataEvent, MergedDataEvent}

import scala.concurrent.{ExecutionContext, Future}

trait AuditConnector {
  def sendEvent(event: AuditEvent)(implicit ec: ExecutionContext): Future[AuditResult]
  def sendEvent(event: DataEvent)(implicit ec: ExecutionContext): Future[AuditResult]
  def sendEvent(event: MergedDataEvent)(implicit ec: ExecutionContext): Future[AuditResult]
}

// TODO Support passing the appName to the handlers
class AuditorImpl(datastreamHandler: AuditHandler) extends AuditConnector {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  var loggingHandler: AuditHandler = LoggingHandler
  var auditSerialiser: AuditSerialiserLike = AuditSerialiser

  def sendEvent(event: AuditEvent)(implicit ec: ExecutionContext): Future[AuditResult] = {
    send(auditSerialiser.serialise(event))
  }

  def sendEvent(event: DataEvent)(implicit ec : ExecutionContext): Future[AuditResult] = {
    send(auditSerialiser.serialise(event))
  }

  def sendEvent(event: MergedDataEvent)(implicit ec : ExecutionContext): Future[AuditResult] = {
    send(auditSerialiser.serialise(event))
  }

  private def send(event: String)(implicit ec: ExecutionContext): Future[AuditResult] = Future {
    try {
      val result: AuditResult = datastreamHandler.sendEvent(event)
      result match {
        case AuditResult.Success =>
          result
        case AuditResult.Rejected =>
          result
        case AuditResult.Failure =>
          loggingHandler.sendEvent(event)
      }
    } catch {
      case e: Throwable =>
        log.error("Error in handler code", e)
        AuditResult.Failure
    }
  }
}

object AuditConnector extends AuditorImpl(DatastreamHandler)
