/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.play.audit.filters

import play.api.http.HeaderNames
import play.api.mvc.{EssentialAction, RequestHeader, ResponseHeader, Result}
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.EventTypes
import uk.gov.hmrc.play.audit.model.DeviceFingerprint
import uk.gov.hmrc.play.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

trait FrontendAuditFilter extends AuditFilter {

  private val textHtml = ".*(text/html).*".r

  def maskedFormFields: Seq[String]

  def applicationPort: Option[Int]

  def buildAuditedHeaders(request: RequestHeader) = HeaderCarrier.fromHeadersAndSession(request.headers, Some(request.session))

  override def apply(nextFilter: EssentialAction) = new EssentialAction {
    def apply(requestHeader: RequestHeader) = {

      val next = nextFilter(requestHeader)
      implicit val hc = HeaderCarrier.fromHeadersAndSession(requestHeader.headers, Some(requestHeader.session))

      val loggingContext = s"${requestHeader.method} ${requestHeader.uri} "

      def performAudit(requestBody: String, maybeResult: Try[Result]): Unit = {
        maybeResult match {
          case Success(result) =>
            val responseHeader = result.header
            getResponseBody(loggingContext, result) map { responseBody =>
              auditConnector.sendEvent(
                dataEvent(EventTypes.RequestReceived, requestHeader.uri, requestHeader)
                  .withDetail(ResponseMessage -> filterResponseBody(responseHeader, new String(responseBody)), StatusCode -> responseHeader.status.toString)
                  .withDetail(buildRequestDetails(requestHeader, requestBody).toSeq: _*)
                  .withDetail(buildResponseDetails(responseHeader).toSeq: _*))
            }
          case Failure(f) =>
            auditConnector.sendEvent(
              dataEvent(EventTypes.RequestReceived, requestHeader.uri, requestHeader)
                .withDetail(FailedRequestMessage -> f.getMessage)
                .withDetail(buildRequestDetails(requestHeader, requestBody).toSeq: _*))
        }
      }

      if (needsAuditing(requestHeader)) {
        onCompleteWithInput(loggingContext, next)(performAudit)
      }
      else next
    }
  }

  private def filterResponseBody(response: ResponseHeader, responseBody: String) = {
    response.headers.get("Content-Type")
      .collect { case textHtml(a) => "<HTML>...</HTML>"}
      .getOrElse(responseBody)
  }

  private def buildRequestDetails(requestHeader: RequestHeader, request: String)(implicit hc: HeaderCarrier): Map[String, String] = {
    val details = new collection.mutable.HashMap[String, String]

    details.put(RequestBody, stripPasswords(requestHeader.contentType, request, maskedFormFields))
    details.put("deviceFingerprint", DeviceFingerprint.deviceFingerprintFrom(requestHeader))
    details.put("host", getHost(requestHeader))
    details.put("port", getPort)
    details.put("queryString", getQueryString(requestHeader.queryString))

    details.toMap
  }

  private def buildResponseDetails(response: ResponseHeader)(implicit hc: HeaderCarrier): Map[String, String] = {
    val details = new collection.mutable.HashMap[String, String]
    response.headers.get(HeaderNames.LOCATION).map { location =>
      details.put(HeaderNames.LOCATION, location)
    }

    details.toMap
  }

  private[filters] def getQueryString(queryString: Map[String, Seq[String]]): String = {
    cleanQueryStringForDatastream(queryString.foldLeft[String]("") {
      (stringRepresentation, mapOfArgs) =>
        val spacer = stringRepresentation match {
          case "" => "";
          case _ => "&"
        }

        stringRepresentation + spacer + mapOfArgs._1 + ":" + getQueryStringValue(mapOfArgs._2)
    })
  }

  private[filters] def getHost(request: RequestHeader) = {
    request.headers.get("Host").map(_.takeWhile(_ != ':')).getOrElse("-")
  }

  private[filters] def getPort = applicationPort.map(_.toString).getOrElse("-")

  private[filters] def stripPasswords(contentType: Option[String], requestBody: String, maskedFormFields: Seq[String]): String = {
    contentType match {
      case Some("application/x-www-form-urlencoded") => maskedFormFields.foldLeft(requestBody)((maskedBody, field) =>
        maskedBody.replaceAll(field + """=.*?(?=&|$|\s)""", field + "=#########"))
      case _ => requestBody
    }
  }

  private def getQueryStringValue(seqOfArgs: Seq[String]): String = {
    seqOfArgs.foldLeft("")(
      (queryStringArrayConcat, queryStringArrayItem) => {
        val queryStringArrayPrepend = queryStringArrayConcat match {
          case "" => ""
          case _ => ","
        }

        queryStringArrayConcat + queryStringArrayPrepend + queryStringArrayItem
      }
    )
  }

  private def cleanQueryStringForDatastream(queryString: String): String = {
    queryString.trim match {
      case "" => "-"
      case ":" => "-" // play 2.5 FakeRequest now parses an empty query string into a two empty string params
      case _ => queryString.trim
    }
  }

}
