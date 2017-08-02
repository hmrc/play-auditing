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

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.AuditResult.Success
import uk.gov.hmrc.play.audit.model.{AuditEvent, DataEvent, MergedDataEvent}

import scala.concurrent.{ExecutionContext, Future}

class MockAuditConnector extends AuditConnector {
  var recordedEvent: Option[AuditEvent] = None
  var recordedMergedEvent: Option[MergedDataEvent] = None

  var sendEventFuture: Future[Success.type] = null
  var sendMergedEventFuture: Future[Success.type] = null

  override def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier, ec : ExecutionContext) = {
    recordedEvent = Some(event)
    Future.successful(AuditResult.Success)
  }

  override def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier, ec : ExecutionContext) = {
    recordedMergedEvent = Some(event)
    Future.successful(AuditResult.Success)
  }

  def reset() = {
    recordedEvent = None
    recordedMergedEvent = None
  }

  override def auditingConfig: AuditingConfig = ???
}
