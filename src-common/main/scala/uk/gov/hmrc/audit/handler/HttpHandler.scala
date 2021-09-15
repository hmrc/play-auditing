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

import uk.gov.hmrc.audit.WSClient
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}


sealed trait HttpResult
object HttpResult {
  case class Response(statusCode: Int) extends HttpResult
  case object Malformed extends HttpResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with HttpResult
}

class HttpHandler(
  endpointUrl: URL,
  wsClient   : WSClient
) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  val HTTP_STATUS_CONTINUE = 100

  def sendHttpRequest(event: JsValue)(implicit ec: ExecutionContext): Future[HttpResult] =
    try {
      logger.debug(s"Sending audit request to URL ${endpointUrl.toString}")

      wsClient.url(endpointUrl.toString)
        .post(event)
        .map { response =>
          val httpStatusCode = response.status
          logger.debug(s"Got status code : $httpStatusCode")
          response.body
          logger.debug("Response processed and closed")

          if (httpStatusCode >= HTTP_STATUS_CONTINUE) {
            logger.info(s"Got status code $httpStatusCode from HTTP server.")
            HttpResult.Response(httpStatusCode)
          } else {
            logger.warn(s"Malformed response (status $httpStatusCode) returned from server")
            HttpResult.Malformed
          }
        }.recover {
          case e: Throwable =>
            HttpResult.Failure("Error opening connection or sending request (async)", Some(e))
        }
    } catch {
      case e: Throwable =>
        Future.successful(HttpResult.Failure("Error opening connection or sending request (sync)", Some(e)))
    }
}
