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

package uk.gov.hmrc.play.audit.http

import com.fasterxml.jackson.core.JsonParseException
import javax.xml.parsers.SAXParserFactory
import org.joda.time.DateTime
import play.api.libs.json._
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{DataCall, MergedDataEvent}
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.time.DateTimeUtils

import scala.xml._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

trait HttpAuditing extends DateTimeUtils {

  val outboundCallAuditType: String = "OutboundCall"

  private val MaskValue = "########"

  def auditConnector: AuditConnector
  def appName: String
  def auditDisabledForPattern: Regex = """http(s)?:\/\/.*\.(service|mdtp)($|[:\/])""".r
  def maskField(field: String): Boolean = {
    val lower = field.toLowerCase
    lower.contains("password") || lower.contains("passwd")
  }

  object AuditingHook extends HttpHook {
    override def apply(url: String, verb: String, body: Option[_], responseF: Future[HttpResponse])(implicit hc: HeaderCarrier, ec : ExecutionContext): Unit = {
      val request = HttpRequest(url, verb, body, now)
      responseF.map {
        response =>
          audit(request, response)
      }.recover {
        case e: Throwable => auditRequestWithException(request, e.getMessage)
      }

    }
  }

  def auditFromPlayFrontend(url: String, response: HttpResponse, hc: HeaderCarrier)(implicit ec: ExecutionContext): Unit = audit(HttpRequest(url, "", None, now), response)(hc, ec)

  private[http] def audit(request: HttpRequest, responseToAudit: HttpResponse)(implicit hc: HeaderCarrier, ex: ExecutionContext): Unit =
    if (isAuditable(request.url)) auditConnector.sendMergedEvent(dataEventFor(request, responseToAudit))

  private[http] def auditRequestWithException(request: HttpRequest, errorMessage: String)(implicit hc: HeaderCarrier, ex: ExecutionContext): Unit =
    if (isAuditable(request.url)) auditConnector.sendMergedEvent(dataEventFor(request, errorMessage))

  private def dataEventFor(request: HttpRequest, errorMesssage: String)(implicit hc: HeaderCarrier) = {
    val responseDetails = Map(FailedRequestMessage -> errorMesssage)
    buildDataEvent(request, responseDetails)
  }

  private def dataEventFor(request: HttpRequest, response: HttpResponse)(implicit hc: HeaderCarrier) = {
    val responseDetails = Map(ResponseMessage -> maskString(response.body), StatusCode -> response.status.toString)
    buildDataEvent(request, responseDetails)
  }

  private def buildDataEvent(request: HttpRequest, responseDetails: Map[String, String])(implicit hc: HeaderCarrier) = {
    import AuditExtensions._

    MergedDataEvent(
      auditSource = appName,
      auditType = outboundCallAuditType,
      request = DataCall(hc.toAuditTags(request.url), requestDetails(request), request.generatedAt),
      response = DataCall(Map.empty, responseDetails, now))
  }

  private def requestDetails(request: HttpRequest)(implicit hc: HeaderCarrier): Map[String, String] = {
    Map(
      "ipAddress" -> hc.forwarded.map(_.value).getOrElse("-"),
      hc.names.authorisation -> hc.authorization.map(_.value).getOrElse("-"),
      hc.names.token -> hc.token.map(_.value).getOrElse("-"),
      Path -> request.url,
      Method -> request.verb) ++
      request.body.map(b => Seq(RequestBody -> maskRequestBody(b))).getOrElse(Seq.empty) ++
      HeaderFieldsExtractor.optionalAuditFields(hc.extraHeaders.toMap)
  }

  private def maskRequestBody(body:Any):String = {
    //The request body comes from calls to executeHooks in http-verbs
    //It is either called with
    // - a Map in the case of a web form
    // - a String created from Json.stringify(wts.writes(... in the case of a class
    // - a String in the case of a string (where the string can be XML)
    body match {
      case m: Map[_, _] => m.map {
        case (key, _) if maskField(key.asInstanceOf[String]) => (key, MaskValue)
        case other => other
      }.toString
      case s: String => maskString(s)
      case other => throw new Exception(s"Unexpected type for requestBody when auditing ${outboundCallAuditType}: ${other.getClass}")
    }
  }

  private def maskString(text:String) = {
    if (text.startsWith("{")) {
      try {
        Json.stringify(maskJsonFields(Json.parse(text)))
      } catch {
        case e:JsonParseException => text
      }
    } else if (text.startsWith("<")) {
      try {
        val builder = new StringBuilder
        PrettyPrinter.format(maskXMLFields(xxeResistantParser.loadString(text)), builder)
        builder.toString()
      } catch {
        case e: SAXParseException => text
      }
    } else {
      text
    }
  }

  private def maskJsonFields(json: JsValue): JsValue = json match {
    case JsObject(fields) => JsObject(fields.map {
      case (key,_) if maskField(key) => (key,JsString(MaskValue))
      case (key,value) => (key,maskJsonFields(value))
    })
    case JsArray(values) => JsArray(values.map(maskJsonFields))
    case other => other
  }

  private def maskXMLFields(elem: Elem):Elem = {
    elem.copy(
      child = elem.child.map { maskXMLFields(_) },
      attributes = maskXMLAttributes(elem.attributes)
    )
  }

  private def maskXMLFields(node:Node):Node = {
    node match {
      case e:Elem if maskField(e.label) => e.copy(child = Seq(Text(MaskValue)), attributes = maskXMLAttributes(e.attributes))
      case e:Elem => e.copy(child = e.child.map(maskXMLFields(_)), attributes = maskXMLAttributes(e.attributes))
      case other => other
    }
  }

  private def maskXMLAttributes(attributes: MetaData):MetaData = {
    attributes.foldLeft(Null: scala.xml.MetaData) { (previous, attr) =>
      attr match {
        case a:PrefixedAttribute if maskField(a.key) => new PrefixedAttribute(a.pre, a.key, MaskValue, previous)
        case a:UnprefixedAttribute if maskField(a.key) => new UnprefixedAttribute(a.key, MaskValue, previous)
        case other => other
      }
    }
  }

  private val PrettyPrinter = new PrettyPrinter(80, 4)

  private val xxeResistantParser = {
    val saxParserFactory = SAXParserFactory.newInstance()
    saxParserFactory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    saxParserFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    saxParserFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    XML.withSAXParser(saxParserFactory.newSAXParser())
  }

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
