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
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json._
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{DataCall, MergedDataEvent}
import uk.gov.hmrc.http.hooks.{Body, HookData, HttpHook, RequestData, ResponseData}
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}

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
      request  : RequestData,
      responseF: Future[ResponseData]
    )(implicit
      hc: HeaderCarrier,
      ec: ExecutionContext
    ): Unit = {
      val httpRequest = HttpRequest(verb, url.toString, request.headers, request.body, now())
      responseF.map {
        response => audit(httpRequest, response)
      }.recover {
        case e: Throwable => auditRequestWithException(httpRequest, e.getMessage)
      }
    }
  }

  private[http] def audit(request: HttpRequest, responseToAudit: ResponseData)(implicit hc: HeaderCarrier, ex: ExecutionContext): Unit =
    if (isAuditable(request.url))
      auditConnector.sendMergedEvent(dataEventFor(request, responseToAudit))

  private[http] def auditRequestWithException(request: HttpRequest, errorMessage: String)(implicit hc: HeaderCarrier, ex: ExecutionContext): Unit =
    if (isAuditable(request.url))
      auditConnector.sendMergedEvent(dataEventFor(request, errorMessage))

  private def dataEventFor(request: HttpRequest, errorMesssage: String)(implicit hc: HeaderCarrier) =
    buildDataEvent(
      request         = request,
      responseDetails = Map(FailedRequestMessage -> errorMesssage)
    )

  private def dataEventFor(httpRequest: HttpRequest, response: ResponseData)(implicit hc: HeaderCarrier) =
    buildDataEvent(
      request         = httpRequest,
      responseDetails = AuditUtils.responseBodyToMap(s"Outbound ${httpRequest.verb} ${httpRequest.url}", response.body)(maskString) ++
                          Map(StatusCode -> response.status.toString)
    )

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

  private def requestDetails(httpRequest: HttpRequest)(implicit hc: HeaderCarrier): Map[String, String] = {
    val caseInsensitiveHeaders = caseInsensitiveMap(httpRequest.headers)

    Map(
      "ipAddress"               -> hc.forwarded.map(_.value).getOrElse("-"),
      HeaderNames.authorisation -> caseInsensitiveHeaders.getOrElse(HeaderNames.authorisation, "-"),
      Path                      -> httpRequest.url,
      Method                    -> httpRequest.verb
    ) ++
      caseInsensitiveHeaders.get(HeaderNames.surrogate).map(HeaderNames.surrogate.toLowerCase -> _).toMap ++
      AuditUtils.requestBodyToMap(s"Outbound ${httpRequest.verb} ${httpRequest.url}", httpRequest.body)(maskRequestBody).filterNot(_ == RequestBody -> "") ++
      when(auditConnector.auditSentHeaders)(
        caseInsensitiveHeaders - HeaderNames.surrogate - HeaderNames.authorisation
      )
  }

  private def maskRequestBody(body: Option[HookData]): String =
    body match {
      case Some(HookData.FromMap(m))    => m.map {
                                             case (key: String, _) if shouldMaskField(key) => (key, MaskValue)
                                             case other                                    => other
                                           }.toString
      case Some(HookData.FromString(s)) => maskString(s)
      case None                         => ""
    }

  // a String could either be Json or XML
  private def maskString(text: String) =
    if (text.startsWith("{"))
      try {
        Json.stringify(maskJsonFields(Json.parse(text)))
      } catch {
        case _: JsonParseException => text
      }
    else if (text.startsWith("<"))
      try {
        val builder = new StringBuilder
        PrettyPrinter.format(maskXMLFields(xxeResistantParser.loadString(text)), builder)
        builder.toString()
      } catch {
        case _: SAXParseException => text
      }
    else
      text

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
    body       : Body[Option[HookData]],
    generatedAt: Instant
  )
}

// Used by bootstrap-play
object HeaderFieldsExtractor {
  def optionalAuditFieldsSeq(headers: Map[String, Seq[String]]): Map[String, String] =
    headers.get(HeaderNames.surrogate).map(HeaderNames.surrogate.toLowerCase -> _.mkString(",")).toMap
}

// functions are reused in bootstrap-play's AuditFilter
object AuditUtils {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  def responseBodyToMap[A](loggingContext: String, body: Body[A])(maskFunction: A => String): Map[String, String] =
    (body match {
        case Body.Complete(b)  => Map(ResponseMessage     -> maskFunction(b))
        case Body.Truncated(b) => logger.warn(s"$loggingContext response body was truncated for auditing")
                                  Map(ResponseMessage     -> maskFunction(b),
                                      ResponseIsTruncated -> true.toString
                                  )
        case Body.Omitted      => Map(ResponseIsOmitted   -> true.toString)
     })

  def requestBodyToMap[A](loggingContext: String, body: Body[A])(maskFunction: A => String): Map[String, String] =
    (body match {
        case Body.Complete (b) => Map(RequestBody        -> maskFunction(b))
        case Body.Truncated(b) => logger.warn(s"$loggingContext request body was truncated for auditing")
                                  Map(RequestBody        -> maskFunction(b),
                                      RequestIsTruncated -> true.toString
                                  )
        case Body.Omitted      => Map(RequestIsOmitted   -> true.toString)
      })
}
