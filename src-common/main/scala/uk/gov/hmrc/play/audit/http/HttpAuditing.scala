/*
 * Copyright 2022 HM Revenue & Customs
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

import java.net.URL
import java.time.Instant

import com.fasterxml.jackson.core.JsonParseException
import javax.xml.parsers.SAXParserFactory
import play.api.libs.json._
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{DataCall, MergedDataEvent}
import uk.gov.hmrc.http.hooks.{HookData, HttpHook}
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, HttpResponse}

import scala.collection.immutable.SortedMap
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
      verb     : String,
      url      : URL,
      headers  : Seq[(String, String)],
      body     : Option[HookData],
      responseF: Future[HttpResponse]
    )(implicit
      hc: HeaderCarrier,
      ec : ExecutionContext
    ): Unit = {
      val request = HttpRequest(verb, url.toString, headers, body, now())
      responseF.map {
        response => audit(request, response)
      }.recover {
        case e: Throwable => auditRequestWithException(request, e.getMessage)
      }
    }
  }

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

  private def when[K, V](pred: Boolean)(value: => Map[K, V]): Map[K, V] =
    if (pred) value else Map.empty

  private[http] def caseInsensitiveMap(headers: Seq[(String, String)]): SortedMap[String, String] =
    SortedMap()(Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER)) ++
      headers.groupBy(_._1.toLowerCase).map{ case (_, hdrs) => hdrs.head._1 -> hdrs.map(_._2).mkString(",")}

  private def requestDetails(request: HttpRequest)(implicit hc: HeaderCarrier): Map[String, String] = {
    val caseInsensitiveHeaders = caseInsensitiveMap(request.headers)

    Map(
      "ipAddress"               -> hc.forwarded.map(_.value).getOrElse("-"),
      HeaderNames.authorisation -> caseInsensitiveHeaders.getOrElse(HeaderNames.authorisation, "-"),
      Path                      -> request.url,
      Method                    -> request.verb
    ) ++
      caseInsensitiveHeaders.get(HeaderNames.surrogate).map(HeaderNames.surrogate.toLowerCase -> _).toMap ++
      request.body.map(b => RequestBody -> maskRequestBody(b)).toMap ++
      when(auditConnector.auditSentHeaders)(caseInsensitiveHeaders - HeaderNames.surrogate - HeaderNames.authorisation)
  }

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
        case _: JsonParseException => text
      }
    } else if (text.startsWith("<")) {
      try {
        val builder = new StringBuilder
        PrettyPrinter.format(maskXMLFields(xxeResistantParser.loadString(text)), builder)
        builder.toString()
      } catch {
        case _: SAXParseException => text
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
    verb       : String,
    url        : String,
    headers    : Seq[(String, String)],
    body       : Option[HookData],
    generatedAt: Instant
  )
}

// TODO: Remove
object HeaderFieldsExtractor {
  def optionalAuditFieldsSeq(headers: Map[String, Seq[String]]): Map[String, String] =
    headers.get(HeaderNames.surrogate).map(HeaderNames.surrogate.toLowerCase -> _.mkString(",")).toMap
}
