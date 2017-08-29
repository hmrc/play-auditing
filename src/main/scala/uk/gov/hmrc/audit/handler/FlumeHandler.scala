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

package uk.gov.hmrc.audit.handler

import java.net.URL

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.hmrc.audit.HandlerResult
import uk.gov.hmrc.audit.HandlerResult.{Failure, Rejected, Success}

class FlumeHandler(url: URL, connectTimeout: Integer, requestTimeout: Integer)
  extends HttpHandler(url, connectTimeout, requestTimeout)
    with AuditHandler {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  override def sendEvent(event: String): HandlerResult = {
    sendEvent(event, retryIfMalformed = true)
  }

  private def sendEvent(event: String, retryIfMalformed: Boolean): HandlerResult = {
    val result = sendHttpRequest(event)
    result match {
      case HttpResult.Response(status) =>
        status match {
          case 200 =>
            logger.debug("Successful response from Flume")
            Success
          case 400 =>
            logger.warn("Malformed request rejected by Flume")
            Rejected
          case 503 =>
            logger.error("Flume is full and cannot accept the event")
            Failure
          case _ =>
            logger.error(s"Unknown return value $status")
            Failure
        }
      case HttpResult.Malformed =>
        if (retryIfMalformed) {
          logger.warn("Malformed response on first request, retrying")
          sendEvent(event, retryIfMalformed = false)
        } else {
          logger.warn("Malformed response on second request, failing")
          Failure
        }
      case HttpResult.Failure(msg, exceptionOption) =>
        exceptionOption match {
          case None =>
            logger.error(msg)
          case Some(ex) =>
            logger.error(msg, ex)
        }
        Failure
    }
  }
}
