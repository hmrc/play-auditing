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
import uk.gov.hmrc.play.audit.model.{DataCall, MergedDataEvent, RedactionLog, TruncationLog}
import uk.gov.hmrc.http.hooks.{Body, HookData, HttpHook, RequestData, ResponseData}
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}

import scala.collection.immutable.SortedMap
import scala.xml._
import scala.concurrent.{ExecutionContext, Future}
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
      )

  private def buildDataEvent(
    request            : HttpRequest,
    response           : Either[String, ResponseData]
  )(implicit hc: HeaderCarrier) = {
    import AuditExtensions._
    val isRequestTruncated =
      request.body.exists(_.isTruncated)
    val (responseDetailsData, isResponseTruncated) =
      response match {
        case Left(errorMessage) => (Data.pure(Map(EventKeys.FailedRequestMessage -> errorMessage)), false)
        case Right(response)    => (maskString(extractFromBody(response.body)).map { maskedResponseMessage =>
                                     Map(
                                       EventKeys.StatusCode      -> response.status.toString,
                                       EventKeys.ResponseMessage -> maskedResponseMessage
                                     ) },
                                     response.body.isTruncated
                                   )
      }

    val truncatedFields =
      (if (isRequestTruncated) List(s"request.detail.${EventKeys.RequestBody}") else List.empty) ++
        (if (isResponseTruncated) List(s"response.detail.${EventKeys.ResponseMessage}") else List.empty)
    if (truncatedFields.nonEmpty)
      logger.info(s"Outbound ${request.verb} ${request.url} - the following fields were truncated for auditing: ${truncatedFields.mkString(", ")}")

    val requestDetailsData =
      requestDetails(request)

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
        extractFromBody(b.map(maskRequestBody)).map(mrb => Map(EventKeys.RequestBody -> mrb))
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
  private def maskString(text: String): Data[String] =
    if (text.startsWith("{"))
      try {
        maskJsonFields(Json.parse(text)).map(Json.stringify)
      } catch {
        case _: JsonParseException => Data.pure(text)
      }
    else if (text.startsWith("<"))
      try {
        maskXMLFields(xxeResistantParser.loadString(text))
          .map { node =>
            val builder = new StringBuilder
            PrettyPrinter.format(node, builder)
            builder.toString()
          }
      } catch {
        case _: SAXParseException => Data.pure(text)
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
    body       : Option[Body[HookData]],
    generatedAt: Instant
  )

  private def extractFromBody[A](body: Body[A]): A =
    body match {
      case Body.Complete (b) => b
      case Body.Truncated(b) => b
    }
}

// Used by bootstrap-play
object HeaderFieldsExtractor {
  def optionalAuditFieldsSeq(headers: Map[String, Seq[String]]): Map[String, String] =
    headers.get(HeaderNames.surrogate).map(HeaderNames.surrogate.toLowerCase -> _.mkString(",")).toMap
}

final case class Data[+A](value: A, isRedacted: Boolean) {

  def map[B](f: A => B): Data[B] =
    flatMap(a => Data.pure(f(a)))

  def map2[B, C](data: Data[B])(f: (A, B) => C): Data[C] =
    flatMap(a => data.map(b => f(a, b)))

  def flatMap[B](f: A => Data[B]): Data[B] = {
    val dataB = f(value)
    Data(
      value      = dataB.value,
      isRedacted = isRedacted || dataB.isRedacted
    )
  }
}

object Data {

  def pure[A](value: A): Data[A] =
    Data(value, isRedacted = false)

  def redacted[A](value: A): Data[A] =
    Data(value, isRedacted = true)

  def traverse[A, B](seq: Seq[A])(f: A => Data[B]): Data[Seq[B]] =
    seq.foldLeft(Data.pure(Seq.empty[B]))((acc, x) => acc.map2(f(x))(_ :+ _))
}
