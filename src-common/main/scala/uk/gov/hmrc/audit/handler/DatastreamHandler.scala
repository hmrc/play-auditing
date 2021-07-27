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

package uk.gov.hmrc.audit.handler

import java.net.URL

import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.JsValue
import uk.gov.hmrc.audit.{HandlerResult, WSClient}
import uk.gov.hmrc.audit.HandlerResult.{Failure, Rejected, Success}

import scala.concurrent.{ExecutionContext, Future}

class DatastreamHandler(
  scheme  : String,
  host    : String,
  port    : Integer,
  path    : String,
  wsClient: WSClient,
  logger : Logger = LoggerFactory.getLogger(getClass)
) extends HttpHandler(
  endpointUrl = new URL(s"$scheme://$host:$port$path"),
  wsClient    = wsClient
) with AuditHandler {

  override def sendEvent(event: JsValue)(implicit ec: ExecutionContext): Future[HandlerResult] =
    sendEvent(event, retryIfMalformed = true)

  private def sendEvent(event: JsValue, retryIfMalformed: Boolean)(implicit ec: ExecutionContext): Future[HandlerResult] =
    sendHttpRequest(event).flatMap {
      case HttpResult.Response(status) =>
        Future.successful(status match {
          case status if 200 <= status && status <= 299 => Success
          case 400 => logger.warn(s"PLAY_AUDIT_REJECTED: received response with $status status code")
                      Rejected
          case 413 => logger.warn(s"PLAY_AUDIT_REJECTED: received response with $status status code")
                      Rejected
          case _   => logger.warn(s"PLAY_AUDIT_FAILURE: received response with $status status code")
                      Failure
        })
      case HttpResult.Malformed =>
          logger.warn("PLAY_AUDIT_FAILURE: received malformed response")
          Future.successful(Failure)
      case HttpResult.Failure(msg, exceptionOption) =>
        exceptionOption match {
          case None     => logger.warn(s"PLAY_AUDIT_FAILURE: failed with error '$msg'")
          case Some(ex) => logger.warn(s"PLAY_AUDIT_FAILURE: failed with error '$msg'", ex)
        }
        Future.successful(Failure)
    }
}
