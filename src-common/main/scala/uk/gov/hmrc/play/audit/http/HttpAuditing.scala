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
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{DataCall, MergedDataEvent, TruncationLog}
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
    ): Unit =
      // short-circuit the payload creation (and truncation log warning)
      if (auditConnector.isEnabled) {
        val httpRequest = HttpRequest(verb, url.toString, request.headers, request.body, now())
        responseF
          .map(Right.apply)
          .recover { case e: Throwable => Left(e.getMessage) }
          .map(audit(httpRequest, _))
      }
  }

  private[http] def audit(request: HttpRequest, responseToAudit: Either[String, ResponseData])(implicit hc: HeaderCarrier, ex: ExecutionContext): Unit =
    if (isAuditable(request.url))
      auditConnector.sendMergedEvent(
        buildDataEvent(
          request  = request,
          response = responseToAudit
        )
      )

  private def buildDataEvent(
    request            : HttpRequest,
    response           : Either[String, ResponseData]
  )(implicit hc: HeaderCarrier) = {
    import AuditExtensions._
    val isRequestTruncated =
      request.body.isTruncated
    val (responseDetails, isResponseTruncated) =
      response match {
        case Left(errorMessage) => (Map(EventKeys.FailedRequestMessage -> errorMessage), false)
        case Right(response)    => val isResponseTruncated =
                                     response.body.isTruncated
                                   val responseBody =
                                     AuditUtils.extractFromBody(
                                       s"Outbound ${request.verb} ${request.url} response",
                                       response.body.map(maskString)
                                     )
                                   (Map(
                                      EventKeys.StatusCode      -> response.status.toString,
                                      EventKeys.ResponseMessage -> responseBody
                                    ),
                                    isResponseTruncated
                                   )
      }
    MergedDataEvent(
      auditSource   = appName,
      auditType     = outboundCallAuditType,
      request       = DataCall(
                        tags        = hc.toAuditTags(request.url),
                        detail      = requestDetails(request),
                        generatedAt = request.generatedAt
                      ),
      response      = DataCall(
                        tags        = Map.empty,
                        detail      = responseDetails,
                        generatedAt = now()
                      ),
      truncationLog = { val truncatedFields =
                          (if (isRequestTruncated) List("request.detail.requestBody") else List.empty) ++
                          (if (isResponseTruncated) List("response.detail.responseMessage") else List.empty)
                        if (truncatedFields.nonEmpty) Some(TruncationLog(truncatedFields, now())) else None
                      }
    )
  }

  private def when[K, V](pred: Boolean)(value: => Map[K, V]): Map[K, V] =
    if (pred) value else Map.empty

  private[http] def caseInsensitiveMap(headers: Seq[(String, String)]): SortedMap[String, String] =
    SortedMap()(Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER)) ++
      headers.groupBy(_._1.toLowerCase).map{ case (_, hdrs) => hdrs.head._1 -> hdrs.map(_._2).mkString(",")}

  private def requestDetails(httpRequest: HttpRequest)(implicit hc: HeaderCarrier): Map[String, String] = {
    val caseInsensitiveHeaders = caseInsensitiveMap(httpRequest.headers)

    val requestBody =
      AuditUtils.extractFromBody(
        s"Outbound ${httpRequest.verb} ${httpRequest.url} request",
        httpRequest.body.map(maskRequestBody)
      )

    Map(
      "ipAddress"               -> hc.forwarded.map(_.value).getOrElse("-"),
      HeaderNames.authorisation -> caseInsensitiveHeaders.getOrElse(HeaderNames.authorisation, "-"),
      EventKeys.Path            -> httpRequest.url,
      EventKeys.Method          -> httpRequest.verb
    ) ++
      caseInsensitiveHeaders.get(HeaderNames.surrogate).map(HeaderNames.surrogate.toLowerCase -> _).toMap ++
      when(requestBody != "")(Map(EventKeys.RequestBody -> requestBody)) ++
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

  def extractFromBody(loggingContext: String, body: Body[String]): String =
    body match {
      case Body.Complete (b) => b
      case Body.Truncated(b) => logger.info(s"$loggingContext request body was truncated for auditing")
                                b
    }
}
