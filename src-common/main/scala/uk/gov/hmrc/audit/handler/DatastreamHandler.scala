/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.audit.HandlerResult.{Failure, Rejected, Success}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

class DatastreamHandler(
  scheme        : String,
  host          : String,
  port          : Integer,
  path          : String,
  connectTimeout: Duration,
  requestTimeout: Duration,
  userAgent     : String
) extends HttpHandler(
  endpointUrl    = new URL(s"$scheme://$host:$port$path"),
  userAgent      = userAgent,
  connectTimeout = connectTimeout,
  requestTimeout = requestTimeout
) with AuditHandler {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  override def sendEvent(event: JsValue)(implicit ec: ExecutionContext): Future[HandlerResult] =
    sendEvent(event, retryIfMalformed = true)

  private def sendEvent(event: JsValue, retryIfMalformed: Boolean)(implicit ec: ExecutionContext): Future[HandlerResult] =
    sendHttpRequest(event).flatMap {
      case HttpResult.Response(status) =>
        Future.successful(status match {
          case 204 => Success
          case 400 => logger.warn("Malformed request rejected by Datastream")
                      Rejected
          case 413 => logger.warn("Too large request rejected by Datastream")
                      Rejected
          case _   => logger.error(s"Unknown return value $status")
                      Failure
        })
      case HttpResult.Malformed =>
        if (retryIfMalformed) {
          logger.warn("Malformed response on first request, retrying")
          sendEvent(event, retryIfMalformed = false)
        } else {
          logger.warn("Malformed response on second request, failing")
          Future.successful(Failure)
        }
      case HttpResult.Failure(msg, exceptionOption) =>
        exceptionOption match {
          case None     => logger.error(msg)
          case Some(ex) => logger.error(msg, ex)
        }
        Future.successful(Failure)
    }
}
