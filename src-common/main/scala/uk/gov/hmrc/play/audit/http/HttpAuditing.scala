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

import java.time.Instant

import com.fasterxml.jackson.core.JsonParseException
import javax.xml.parsers.SAXParserFactory
import play.api.Configuration
import play.api.libs.json._
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{DataCall, MergedDataEvent}
import uk.gov.hmrc.http.hooks.{HookData, HttpHook}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import scala.xml._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

trait HttpAuditing {
  val outboundCallAuditType: String = "OutboundCall"

  private val MaskValue = "########"

  protected def now(): Instant = Instant.now()

  def auditConnector: AuditConnector

  def appName: String

  /** clients may want to override */
  def auditDisabledForPattern: Regex =
    """http(s)?:\/\/.*\.(service|mdtp)($|[:\/])""".r

  def shouldMaskField(field: String): Boolean = {
    val lower = field.toLowerCase
    lower.contains("password") || lower.contains("passwd")
  }

  object AuditingHook extends HttpHook {
    override def apply(
      url      : String,
      verb     : String,
      body     : Option[HookData],
      responseF: Future[HttpResponse]
    )(implicit
      hc: HeaderCarrier,
      ec : ExecutionContext
    ): Unit = {
      val request = HttpRequest(url, verb, body, now())
      responseF.map {
        response => audit(request, response)
      }.recover {
        case e: Throwable => auditRequestWithException(request, e.getMessage)
      }
    }
  }

  def auditFromPlayFrontend(url: String, response: HttpResponse, hc: HeaderCarrier)(implicit ec: ExecutionContext): Unit =
    audit(HttpRequest(url, "", None, Instant.now), response)(hc, ec)

  private[http] def audit(request: HttpRequest, responseToAudit: HttpResponse)(implicit hc: HeaderCarrier, ex: ExecutionContext): Unit =
    if (isAuditable(request.url)) auditConnector.sendMergedEvent(dataEventFor(request, responseToAudit))

  private[http] def auditRequestWithException(request: HttpRequest, errorMessage: String)(implicit hc: HeaderCarrier, ex: ExecutionContext): Unit =
    if (isAuditable(request.url)) auditConnector.sendMergedEvent(dataEventFor(request, errorMessage))

  private def dataEventFor(request: HttpRequest, errorMesssage: String)(implicit hc: HeaderCarrier) = {
    val responseDetails = Map(FailedRequestMessage -> errorMesssage)
    buildDataEvent(request, responseDetails)
  }

  private def dataEventFor(request: HttpRequest, response: HttpResponse)(implicit hc: HeaderCarrier) = {
    val responseDetails =
      Map(
        ResponseMessage -> maskString(response.body),
        StatusCode      -> response.status.toString
      )
    buildDataEvent(request, responseDetails)
  }

  private def buildDataEvent(request: HttpRequest, responseDetails: Map[String, String])(implicit hc: HeaderCarrier) = {
    import AuditExtensions._

    MergedDataEvent(
      auditSource = appName,
      auditType   = outboundCallAuditType,
      request     = DataCall(
                      tags        = hc.toAuditTags(request.url),
                      detail      = requestDetails(request),
                      generatedAt = request.generatedAt
                    ),
      response    = DataCall(
                      tags        = Map.empty,
                      detail      = responseDetails,
                      generatedAt = now()
                    )
    )
  }

  private def requestDetails(request: HttpRequest)(implicit hc: HeaderCarrier): Map[String, String] =
    Map(
      "ipAddress"            -> hc.forwarded.map(_.value).getOrElse("-"),
      hc.names.authorisation -> hc.authorization.map(_.value).getOrElse("-"),
      hc.names.token         -> hc.token.map(_.value).getOrElse("-"),
      Path                   -> request.url,
      Method                 -> request.verb
    ) ++
      request.body.map(b => Seq(RequestBody -> maskRequestBody(b))).getOrElse(Seq.empty) ++
      auditExtraHeaderFields(hc.extraHeaders.toMap)

  private def maskRequestBody(body: HookData): String =
    body match {
      case HookData.FromMap(m)    => m.map {
                                       case (key: String, _) if shouldMaskField(key) => (key, MaskValue)
                                       case other                                    => other
                                     }.toString
      case HookData.FromString(s) => maskString(s)
    }

  // a String could either be Json or XML
  private def maskString(text: String) =
    if (text.startsWith("{")) {
      try {
        Json.stringify(maskJsonFields(Json.parse(text)))
      } catch {
        case e: JsonParseException => text
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

  private def maskJsonFields(json: JsValue): JsValue =
    json match {
      case JsObject(fields) => JsObject(
                                 fields.map { case (key, value) =>
                                   (key,
                                    if (shouldMaskField(key)) JsString(MaskValue)
                                    else maskJsonFields(value)
                                   )
                                 }
                               )
      case JsArray(values)  => JsArray(values.map(maskJsonFields))
      case other            => other
    }

  private def maskXMLFields(node: Node): Node =
    node match {
      case e: Elem   => e.copy(
                          child      = if (shouldMaskField(e.label)) Seq(Text(MaskValue))
                                       else e.child.map(maskXMLFields),
                          attributes = maskXMLAttributes(e.attributes)
                        )
      case other     => other
    }

  private def maskXMLAttributes(attributes: MetaData): MetaData =
    attributes.foldLeft(Null: scala.xml.MetaData) { (previous, attr) =>
      attr match {
        case a: PrefixedAttribute   if shouldMaskField(a.key) => new PrefixedAttribute(a.pre, a.key, MaskValue, previous)
        case a: UnprefixedAttribute if shouldMaskField(a.key) => new UnprefixedAttribute(a.key, MaskValue, previous)
        case other                                            => other
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

  private def isAuditable(url: String) =
    !url.contains("/write/audit") && auditDisabledForPattern.findFirstIn(url).isEmpty

  protected case class HttpRequest(
    url        : String,
    verb       : String,
    body       : Option[HookData],
    generatedAt: Instant
  )

  private def auditExtraHeaderFields(headers: Map[String, String]): Map[String, String] =
    headers.map(t => t._1 -> Seq(t._2)).collect {
      case ("Surrogate", v) => "surrogate" -> v.mkString(",")
      case (k, v) if auditConnector.auditExtraHeaders ⇒ k -> v.mkString(",")
    }
}

// TODO: Remove
object HeaderFieldsExtractor {
  private val SurrogateHeader = "Surrogate"

  def optionalAuditFields(headers: Map[String, String]): Map[String, String] =
    optionalAuditFieldsSeq(
      headers.map(t => t._1 -> Seq(t._2))
    )

  def optionalAuditFieldsSeq(headers: Map[String, Seq[String]]): Map[String, String] =
    headers.collect {
      case (SurrogateHeader, v) => "surrogate" -> v.mkString(",")
    }
}