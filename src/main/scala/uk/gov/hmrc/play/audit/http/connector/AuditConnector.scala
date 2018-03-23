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
import uk.gov.hmrc.audit.AuditResult
import uk.gov.hmrc.audit.connector.AuditorImpl
import uk.gov.hmrc.audit.handler.{AuditHandler, DatastreamHandler}
import uk.gov.hmrc.audit.model.AuditEvent
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.model.{DataEvent, MergedDataEvent}

import scala.concurrent.{ExecutionContext, Future}

/*
 * Wrapper object that supports Play configuration of auditing.
 */
trait AuditConnector {
  def auditingConfig: AuditingConfig

  val defaultConnectionTimeout = 5000
  val defaultRequestTimeout = 5000
  val defaultBaseUri = BaseUri("datstream.protected.mdtp", 90, "http")

  val consumer: Consumer = auditingConfig.consumer.getOrElse(Consumer(defaultBaseUri))
  val baseUri: BaseUri = consumer.baseUri
  val datastreamHandler: AuditHandler = new DatastreamHandler(baseUri.protocol, baseUri.host,
    baseUri.port, s"/write/audit", defaultConnectionTimeout, defaultRequestTimeout)

  def auditorImpl: uk.gov.hmrc.audit.connector.AuditConnector = new AuditorImpl(datastreamHandler)

  private val log: Logger = LoggerFactory.getLogger(getClass)

  // TODO Cleanup this cut and paste code
  def sendEvent(event: DataEvent)(implicit ec : ExecutionContext): Future[AuditResult] = {
    if (auditingConfig.enabled && !event.tags.getOrElse(EventKeys.Path, "").startsWith("/ping/ping")) {
      auditorImpl.sendEvent(event)
    } else {
      val requestId = event.tags.get(HeaderNames.xRequestId)
      val sessionId = event.tags.get(HeaderNames.xSessionId)
      log.info(s"auditing disabled for request-id $requestId, session-id: $sessionId")
      Future.successful(AuditResult.Success)
    }
  }

  def sendEvent(event: MergedDataEvent)(implicit ec : ExecutionContext): Future[AuditResult] = {
    if (auditingConfig.enabled && !event.request.tags.getOrElse(EventKeys.Path, "").startsWith("/ping/ping")) {
      auditorImpl.sendEvent(event)
    } else {
      val requestId = event.request.tags.get(HeaderNames.xRequestId)
      val sessionId = event.request.tags.get(HeaderNames.xSessionId)
      log.info(s"auditing disabled for request-id $requestId, session-id: $sessionId")
      Future.successful(AuditResult.Success)
    }
  }

  def sendEvent(event: AuditEvent)(implicit ec : ExecutionContext): Future[AuditResult] = {
    if (auditingConfig.enabled && !event.path.startsWith("/ping/ping")) {
      auditorImpl.sendEvent(event)
    } else {
      val requestId = event.requestID
      val sessionId = event.sessionID
      log.info(s"auditing disabled for request-id $requestId, session-id: $sessionId")
      Future.successful(AuditResult.Success)
    }
  }
}