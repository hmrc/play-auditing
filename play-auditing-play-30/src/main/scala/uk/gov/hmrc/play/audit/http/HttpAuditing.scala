/*
 * Copyright 2023 HM Revenue & Customs
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
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.{DataCall, MergedDataEvent, RedactionLog, TruncationLog}
import uk.gov.hmrc.http.hooks.{Data, HookData, HttpHook, RequestData, ResponseData}
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}

import scala.collection.immutable.SortedMap
import scala.xml._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.util.matching.Regex

trait HttpAuditing {
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  val outboundCallAuditType: String = "OutboundCall"

  private val MaskValue = "########"

  protected def now(): Instant = Instant.now()

  def auditConnector: AuditConnector

  def appName: String

  /** clients may want to override */
  def auditDisabledForPattern: Regex =
    """http(s)?:\/\/.*\.(service|mdtp)($|[:\/])""".r

  /** clients may want to override */
  def fieldMaskPattern: Regex =
    "(?i).*(password|passwd).*".r

  def shouldMaskField(field: String): Boolean =
    fieldMaskPattern.matches(field)

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
      // short-circuit the payload creation
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
      ).onComplete {
        case Success(AuditResult.Success | AuditResult.Disabled)  =>
        case Success(AuditResult.Failure(msg, _))                 => // already logged
        case Failure(ex)                                          => logger.error(s"Failed to audit http event: ${ex.getMessage}", ex)
      }

  private def buildDataEvent(
    request            : HttpRequest,
    response           : Either[String, ResponseData]
  )(implicit hc: HeaderCarrier) = {
    import AuditExtensions._
    val requestDetailsData =
      requestDetails(request)

    val responseDetailsData =
      response match {
        case Left(errorMessage) =>
          Data.pure(Map(EventKeys.FailedRequestMessage -> errorMessage))
        case Right(response) =>
          for {
            responseBody <- response.body
            maskedResponseBody <- maskString(responseBody)
          } yield
            Map(
              EventKeys.StatusCode      -> response.status.toString,
              EventKeys.ResponseMessage -> maskedResponseBody
            )
      }

    val truncatedFields =
      (if (requestDetailsData.isTruncated) List(s"request.detail.${EventKeys.RequestBody}") else List.empty) ++
        (if (responseDetailsData.isTruncated) List(s"response.detail.${EventKeys.ResponseMessage}") else List.empty)
    if (truncatedFields.nonEmpty)
      logger.info(s"Outbound ${request.verb} ${request.url} - the following fields were truncated for auditing: ${truncatedFields.mkString(", ")}")


    val redactedFields =
      (if (requestDetailsData.isRedacted) List(s"request.detail.${EventKeys.RequestBody}") else List.empty) ++
        (if (responseDetailsData.isRedacted) List(s"response.detail.${EventKeys.ResponseMessage}") else List.empty)

    MergedDataEvent(
      auditSource   = appName,
      auditType     = outboundCallAuditType,
      request       = DataCall(
                        tags        = hc.toAuditTags(request.url),
                        detail      = requestDetailsData.value,
                        generatedAt = request.generatedAt
                      ),
      response      = DataCall(
                        tags        = Map.empty,
                        detail      = responseDetailsData.value,
                        generatedAt = now()
                      ),
      truncationLog = TruncationLog.of(truncatedFields),
      redactionLog  = RedactionLog.of(redactedFields)
    )
  }

  private def when[K, V](pred: Boolean)(value: => Map[K, V]): Map[K, V] =
    if (pred) value else Map.empty

  private[http] def caseInsensitiveMap(headers: Seq[(String, String)]): SortedMap[String, String] =
    SortedMap()(Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER)) ++
      headers.groupBy(_._1.toLowerCase).map{ case (_, hdrs) => hdrs.head._1 -> hdrs.map(_._2).mkString(",")}

  private def requestDetails(httpRequest: HttpRequest)(implicit hc: HeaderCarrier): Data[Map[String, String]] = {
    val maskedRequestBody =
      httpRequest.body.fold(Data.pure(Map.empty[String, String]))(b =>
        b.flatMap(maskRequestBody).map(mrb => Map(EventKeys.RequestBody -> mrb))
      )

    maskedRequestBody.map { mrb =>
      val caseInsensitiveHeaders = caseInsensitiveMap(httpRequest.headers)
      Map(
        "ipAddress"               -> hc.forwarded.map(_.value).getOrElse("-"),
        HeaderNames.authorisation -> caseInsensitiveHeaders.getOrElse(HeaderNames.authorisation, "-"),
        EventKeys.Path            -> httpRequest.url,
        EventKeys.Method          -> httpRequest.verb
      ) ++
        caseInsensitiveHeaders.get(HeaderNames.surrogate).map(HeaderNames.surrogate.toLowerCase -> _).toMap ++ mrb ++
        when(auditConnector.auditSentHeaders)(
          caseInsensitiveHeaders - HeaderNames.surrogate - HeaderNames.authorisation
        )
    }
  }

  private def maskRequestBody(body: HookData): Data[String] =
    body match {
      case HookData.FromMap(m) =>
        Data.traverse(m.toSeq) {
          case (key, _) if shouldMaskField(key) => Data.redacted(key -> MaskValue)
          case other                            => Data.pure(other)
        }.map(_.toMap.toString())
      case HookData.FromString(s) =>
        maskString(s)
    }

  // a String could either be Json or XML
  private[http] def maskString(text: String): Data[String] =
    if (text.startsWith("{"))
      try {
        maskJsonFields(Json.parse(text)).map(Json.stringify)
      } catch {
        case _: JsonParseException => Data.pure(text)
      }
    else if (text.startsWith("<"))
      try {
        maskXMLFields(xxeResistantParser().loadString(text))
          .map { node =>
            val builder = new StringBuilder
            prettyPrinter().format(node, builder)
            builder.toString()
          }
      } catch {
        case _: SAXParseException => Data.pure(text)
        case e: Throwable         => logger.error(s"Unexpected error parsing xml: ${e.getMessage}", e)
                                     Data.pure(text)
      }
    else
      Data.pure(text)

  private def maskJsonFields(json: JsValue): Data[JsValue] =
    json match {
      case JsObject(fields) =>
          Data.traverse(fields.toSeq) { case (key, value) =>
            if (shouldMaskField(key))
              Data.redacted(key -> JsString(MaskValue))
            else
              maskJsonFields(value).map(key -> _)
          }.map(JsObject(_))
      case JsArray(values)   =>
        Data.traverse(values.toSeq)(maskJsonFields).map(JsArray(_))
      case other =>
        Data.pure(other)
    }

  private def maskXMLFields(node: Node): Data[Node] =
    node match {
      case e: Elem =>
        for {
          child      <- if (shouldMaskField(e.label))
                          Data.redacted(Seq(Text(MaskValue)))
                        else
                          Data.traverse(e.child.toSeq)(maskXMLFields)
          attributes <- maskXMLAttributes(e.attributes)
        } yield e.copy(child = child, attributes = attributes)
      case other =>
        Data.pure(other)
    }

  private def maskXMLAttributes(attributes: MetaData): Data[MetaData] =
    attributes.foldLeft(Data.pure(Null: scala.xml.MetaData)) { (previous, attr) =>
      attr match {
        case a: PrefixedAttribute   if shouldMaskField(a.key) => Data.redacted(new PrefixedAttribute(a.pre, a.key, MaskValue, previous.value))
        case a: UnprefixedAttribute if shouldMaskField(a.key) => Data.redacted(new UnprefixedAttribute(a.key, MaskValue, previous.value))
        case other                                            => previous.flatMap(_ => Data.pure(other))
      }
    }

  private def prettyPrinter() = // not val since is not thread-safe
    new PrettyPrinter(80, 4)

  private def xxeResistantParser() = {  // not val since is not thread-safe
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
    body       : Option[Data[HookData]],
    generatedAt: Instant
  )
}

// Used by bootstrap-play
object HeaderFieldsExtractor {
  def optionalAuditFieldsSeq(headers: Map[String, Seq[String]]): Map[String, String] =
    headers.get(HeaderNames.surrogate).map(HeaderNames.surrogate.toLowerCase -> _.mkString(",")).toMap
}
