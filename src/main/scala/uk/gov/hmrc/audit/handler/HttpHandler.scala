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

import java.io.{IOException, OutputStream}
import java.net.{HttpURLConnection, URL}

import org.slf4j.{Logger, LoggerFactory}

sealed trait HttpResult
object HttpResult {
  case class Response(statusCode: Int) extends HttpResult
  case object Malformed extends HttpResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with HttpResult
}

abstract class HttpHandler(
  endpointUrl      : URL,
  userAgent        : String,
  connectTimeout   : Integer = 5000,
  requestTimeout   : Integer = 5000,
  contentTypeHeader: String  = "application/json",
  acceptHeader     : String  = "application/json"
) {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  val HTTP_STATUS_CONTINUE = 100

  def sendHttpRequest(event: String): HttpResult = {
    var outputStream: OutputStream = null

    try {
      if (event != null && event.nonEmpty) {
        logger.debug(s"Sending audit request to URL ${endpointUrl.toString}")

        try {
          val connection = getConnection
          outputStream = connection.getOutputStream
          outputStream.write(event.getBytes)
          outputStream.flush()
          outputStream.close()

          val httpStatusCode = connection.getResponseCode
          logger.debug(s"Got status code : $httpStatusCode")

          try {
            // FIXME Not great if this class is meant to be generic...
            val inputStream = connection.getInputStream
            if (inputStream != null) {
              inputStream.close()
            }
          } catch {
            case e: IOException =>
              // do nothing here as we do not care about the response
          }
          logger.debug("Response processed and closed")

          if (httpStatusCode >= HTTP_STATUS_CONTINUE) {
            logger.info(s"Got status code $httpStatusCode from HTTP server.")
            HttpResult.Response(httpStatusCode)
          }
          else {
            logger.warn("Malformed response returned from server")
            HttpResult.Malformed
          }
        } catch {
          case e: IOException =>
            HttpResult.Failure("Error opening connection, or request timed out", Some(e))
        }
      }
      else {
        HttpResult.Failure("Content was empty")
      }
    } catch {
      case t: Throwable =>
        HttpResult.Failure("Error sending HTTP request", Some(t))
    } finally {
      if (outputStream != null) try
        outputStream.close()
      catch {
        case e: IOException =>
          // ignore errors
      }
    }
  }

  @throws[IOException]
  def getConnection: HttpURLConnection = {
    val connection = endpointUrl.openConnection.asInstanceOf[HttpURLConnection]
    connection.setRequestMethod("POST")
    connection.setRequestProperty("Content-Type", contentTypeHeader)
    connection.setRequestProperty("Accept", acceptHeader)
    connection.setRequestProperty("User-Agent", userAgent)
    connection.setConnectTimeout(connectTimeout)
    connection.setReadTimeout(requestTimeout)
    connection.setDoOutput(true)
    connection.setDoInput(true)
    connection.connect()
    connection
  }
}
