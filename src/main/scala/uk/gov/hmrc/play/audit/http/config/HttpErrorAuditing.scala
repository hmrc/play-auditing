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

package uk.gov.hmrc.play.audit.http.config

import play.api.http.HttpErrorHandler
import play.api.http.Status._
import play.api.mvc.{RequestHeader, Result}
import uk.gov.hmrc.play.audit.EventTypes._
import uk.gov.hmrc.play.audit.http.HttpAuditEvent
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.http.{JsValidationException, NotFoundException}

import scala.concurrent.{ExecutionContext, Future}

trait HttpErrorAuditing extends HttpErrorHandler with HttpAuditEvent {

  def auditConnector: AuditConnector
  implicit def ec: ExecutionContext

  private val unexpectedError = "Unexpected error"
  private val notFoundError = "Resource Endpoint Not Found"
  private val badRequestError = "Request bad format exception"

  abstract override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = {

    statusCode match {
      case NOT_FOUND =>
        auditConnector.sendEvent(dataEvent(ResourceNotFound, notFoundError, request))
      case _ =>
        auditConnector.sendEvent(dataEvent(ServerValidationError, badRequestError, request))
    }

    super.onClientError(request, statusCode, message)
  }

  abstract override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {

    val code = exception match {
      case _: NotFoundException => ResourceNotFound
      case _: JsValidationException => ServerValidationError
      case _ => ServerInternalError
    }

    auditConnector.sendEvent(dataEvent(code, unexpectedError, request)
      .withDetail(TransactionFailureReason -> exception.getMessage))

    super.onServerError(request, exception)
  }
}
