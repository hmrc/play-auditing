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

package uk.gov.hmrc.play.audit.http

import org.joda.time.DateTime
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{DataCall, MergedDataEvent}
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

trait HttpAuditing extends DateTimeUtils {

  val outboundCallAuditType: String = "OutboundCall"

  def auditConnector: AuditConnector
  def appName: String
  def auditDisabledForPattern: Regex = """http(s)?:\/\/.*\.(service|mdtp)($|[:\/])""".r

  object AuditingHook extends HttpHook {
    override def apply(url: String, verb: String, body: Option[_], responseF: Future[HttpResponse])(implicit hc: HeaderCarrier, ec : ExecutionContext): Unit = {
      val request = HttpRequest(url, verb, body, now)
      responseF.map {
        response =>
          audit(request, response)
      }(ec).recover {
        case e: Throwable => auditRequestWithException(request, e.getMessage)
      }(ec)

    }
  }

  def auditFromPlayFrontend(url: String, response: HttpResponse, hc: HeaderCarrier): Unit = audit(HttpRequest(url, "", None, now), response)(hc)

  private[http] def audit(request: HttpRequest, responseToAudit: HttpResponse)(implicit hc: HeaderCarrier): Unit =
    if (isAuditable(request.url)) auditConnector.sendMergedEvent(dataEventFor(request, responseToAudit))

  private[http] def auditRequestWithException(request: HttpRequest, errorMessage: String)(implicit hc: HeaderCarrier): Unit =
    if (isAuditable(request.url)) auditConnector.sendMergedEvent(dataEventFor(request, errorMessage))

  private def dataEventFor(request: HttpRequest, errorMesssage: String)(implicit hc: HeaderCarrier) = {
    val responseDetails = Map(FailedRequestMessage -> errorMesssage)
    buildDataEvent(request, responseDetails)
  }

  private def dataEventFor(request: HttpRequest, response: HttpResponse)(implicit hc: HeaderCarrier) = {
    val responseDetails = Map(ResponseMessage -> response.body, StatusCode -> response.status.toString)
    buildDataEvent(request, responseDetails)
  }

  private def buildDataEvent(request: HttpRequest, responseDetails: Map[String, String])(implicit hc: HeaderCarrier) = {
    import AuditExtensions._

    MergedDataEvent(
      auditSource = appName,
      auditType = outboundCallAuditType,
      request = DataCall(hc.toAuditTags(request.url, request.url), hc.toAuditDetails(requestDetails(request): _*), request.generatedAt),
      response = DataCall(Map.empty, responseDetails, now))
  }

  private def requestDetails(request: HttpRequest)(implicit hc: HeaderCarrier): Seq[(String, String)] =
    Seq(Path -> request.url, Method -> request.verb) ++ request.body.map(b => Seq(RequestBody -> b.toString)).getOrElse(Seq.empty) ++ HeaderFieldsExtractor.optionalAuditFields(hc.extraHeaders.toMap)

  private def isAuditable(url: String) = !url.contains("/write/audit") && auditDisabledForPattern.findFirstIn(url).isEmpty

  protected case class HttpRequest(url: String, verb: String, body: Option[_], generatedAt: DateTime)

}

object HeaderFieldsExtractor {
  private val SurrogateHeader = "Surrogate"

  def optionalAuditFields(headers : Map[String, String]) : Map[String, String] = {
    val map = headers map (t => t._1 -> Seq(t._2))
    optionalAuditFieldsSeq(map)
  }

  def optionalAuditFieldsSeq(headers : Map[String, Seq[String]]) : Map[String, String] = {
    headers.foldLeft(Map[String, String]()) { (existingList : Map[String, String], tup: (String, Seq[String])) =>
      tup match {
        case (SurrogateHeader, _) => existingList + ("surrogate" -> tup._2.mkString(","))
        // Add more optional here
        case _ => existingList
      }
    }
  }
}
