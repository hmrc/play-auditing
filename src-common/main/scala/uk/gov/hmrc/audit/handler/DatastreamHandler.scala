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
import uk.gov.hmrc.play.audit.http.connector.DatastreamMetrics

import scala.concurrent.{ExecutionContext, Future}

class DatastreamHandler(
  scheme  : String,
  host    : String,
  port    : Integer,
  path    : String,
  wsClient: WSClient,
  metrics: DatastreamMetrics,
) extends HttpHandler(
  endpointUrl = new URL(s"$scheme://$host:$port$path"),
  wsClient    = wsClient
) with AuditHandler {

  private[handler] val logger : Logger = LoggerFactory.getLogger(getClass)

  override def sendEvent(event: JsValue)(implicit ec: ExecutionContext): Future[HandlerResult] =
    sendHttpRequest(event).flatMap {
      case HttpResult.Response(status) =>
        Future.successful(status match {
          case status if play.api.http.Status.isSuccessful(status) =>
            metrics.successCounter.inc()
            Success
          case 400 | 413 =>
            metrics.rejectCounter.inc()
            logger.warn(s"AUDIT_REJECTED: received response with $status status code")
            Rejected
          case _   =>
            metrics.failureCounter.inc()
            logger.warn(s"AUDIT_FAILURE: received response with $status status code")
            Failure
        })
      case HttpResult.Malformed =>
        metrics.failureCounter.inc()
        logger.warn("AUDIT_FAILURE: received malformed response")
          Future.successful(Failure)
      case HttpResult.Failure(msg, exceptionOption) =>
        metrics.failureCounter.inc()

        exceptionOption match {
          case None     => logger.warn(s"AUDIT_FAILURE: failed with error '$msg'")
          case Some(ex) => logger.warn(s"AUDIT_FAILURE: failed with error '$msg'", ex)
        }
        Future.successful(Failure)
    }
}
