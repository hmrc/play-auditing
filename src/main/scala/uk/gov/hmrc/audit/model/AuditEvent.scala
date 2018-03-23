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

package uk.gov.hmrc.audit.model

import java.net.URI
import java.util.UUID

import org.joda.time.DateTime
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}
import FieldNames._

/**
  * Class used for all audit events.
  *
  * This is a replacement for all the uk.gov.hmrc.play.audit.model case classes.
  */
class AuditEvent private (
  val auditSource: String,
  val auditType: String,
  val generatedAt: DateTime,
  val eventID: String,
  val requestID: String,
  var sessionID: Option[String],
  val path: String,
  val method: String,
  val queryString: Option[String],
  val clientIP: Option[String],
  val clientPort: Option[Int],
  val receivingIP: Option[String],
  val authorisationToken: Option[String],
  val clientHeaders: Option[Map[String, String]],
  val requestHeaders: Option[Map[String, String]],
  val cookies: Option[Map[String, String]],
  val fields: Option[Map[String, String]],
  val requestPayload: Option[Payload],
  val identifiers: Option[Map[String, String]],
  val enrolments: Option[List[Enrolment]],
  var detail: Option[Map[String, _]],
  val responseHeaders: Option[Map[String, String]],
  val responseStatus: Option[Int],
  val responsePayload: Option[Payload],
  val version: Int) {

  require(requestID != null && !requestID.trim.isEmpty && !requestID.trim.equals("-"),
    "A \"requestID\" is required for all audit events.")
  require(eventID != null && !eventID.trim.isEmpty && !eventID.trim.equals("-"),
    "An \"eventID\" is required for all audit events.")
  require(generatedAt != null, "The \"generatedAt\" date must be populated.")
}

object AuditEvent {
  // TODO Should we default to empty maps and empty strings for most of these fields?
  def apply(auditSource: String,
    auditType: String,
    method: String,
    pathString: String,
    generatedAt: DateTime = DateTime.now,
    detail: Map[String, Any] = Map(),
    identifiers: Option[Map[String, String]] = None,
    enrolments: Option[List[Enrolment]] = None,
    requestHeaders: Option[Map[String, String]] = None,
    fields: Option[Map[String, String]] = None,
    cookies: Option[Map[String, String]] = None,
    requestPayload: Option[Payload] = None,
    responseHeaders: Option[Map[String, String]] = None,
    responsePayload: Option[Payload] = None,
    responseStatus: Option[Int] = None,
    clientIP: Option[String] = None,
    clientPort: Option[Int] = None,
    receivingIP: Option[String] = None,
    requestID: Option[String] = None,
    sessionID: Option[String] = None,
    authorisationToken: Option[String] = None,
    clientHeaders: Option[Map[String, String]] = None,
    version: Int = 1,
    eventID: String = UUID.randomUUID.toString
  )(implicit hc: HeaderCarrier): AuditEvent = {

    val pathTuple = splitPath(pathString)
    val detailRequestBody = getString(detail, LegacyDetailNames.requestBody)
    val detailResponseMessage = getString(detail, LegacyDetailNames.responseMessage)
    val hcAuthorization = hc.authorization.flatMap(authorisation => Some(authorisation.value))

    new AuditEvent(auditSource,
      auditType,
      generatedAt,
      eventID,
      requestID.getOrElse(hc.requestId.flatMap(id => Some(id.value)).getOrElse("")),
      sessionID.orElse(hc.sessionId.flatMap(sessionId => Some(sessionId.value))),
      pathTuple._1,
      detail.getOrElse(LegacyDetailNames.method, method).toString,
      Option(pathTuple._2).filter(_.trim.nonEmpty).orElse(getString(detail, LegacyDetailNames.queryString)),
      clientIP.orElse(hc.trueClientIp.orElse(None)),
      clientPort.orElse(hc.trueClientPort.flatMap(value => Some(value.toInt))),
      receivingIP,
      authorisationToken.orElse(hcAuthorization).orElse(getString(detail, LegacyDetailNames.authorisation)),
      clientHeaders,
      collectRequestHeaders(requestHeaders.getOrElse(Map()), hc),
      cookies,
      fields,
      requestPayload.orElse(Some(Payload("", detailRequestBody.getOrElse("")))),
      collectIdentifiers(detail, identifiers.getOrElse(Map())),
      enrolments,
      collectDetailWithoutIds(detail),
      responseHeaders,
      responseStatus.orElse(getString(detail, LegacyDetailNames.statusCode).flatMap(p => Some(p.toInt))),
      responsePayload.orElse(Some(Payload("", detailResponseMessage.getOrElse("")))),
      version
    )
  }

  private def collectIdentifiers(detail: Map[String, _], identifiers: Map[String, String]): Option[Map[String, String]] = {
    val filteredIds = identifiers.filter(nonEmpty) ++ collectIdsFromDetail(detail)

    if (filteredIds.isEmpty) None else Some(filteredIds)
  }

  private def collectIdsFromDetail(detail: Map[String, _]): Map[String, String] = {
    detail.flatMap(p => {
      if (detailsToIdentifiers.contains(p._1) && p._2.isInstanceOf[String]) {
        val stringValue = p._2.asInstanceOf[String]
        if (nonEmpty(stringValue)) {
          Some((detailsToIdentifiers(p._1), stringValue))
        } else {
          None
        }
      } else {
        None
      }
    })
  }

  private def collectDetailWithoutIds(detail: Map[String, _]): Option[Map[String, _]] = {
    val filteredDetail = detail.filter(p => {
      nonEmpty(p) && !detailsToIdentifiers.contains(p._1)
    })

    if (filteredDetail.isEmpty) None else Some(filteredDetail)
  }

  private def collectRequestHeaders(suppliedRequestHeaders: Map[String, String], hc: HeaderCarrier): Option[Map[String, String]] = {
    val headers = (suppliedRequestHeaders ++ hc.extraHeaders ++ hc.otherHeaders ++
      hc.forwarded.map(f => HeaderNames.xForwardedFor -> f.value) ++
      hc.akamaiReputation.map(f => HeaderNames.akamaiReputation -> f.value)
    ).filter(p => {
      nonEmpty(p) && !excludedRequestHeaders.contains(p._1)
    })

    if (headers.isEmpty) None else Some(headers)
  }

  def nonEmpty(value: String): Boolean = {
    value.trim match {
      case "" => false
      case "-" => false
      case _ => true
    }
  }

  def nonEmpty(tuple: (String, _)): Boolean = {
    tuple._1.trim.nonEmpty &&
      ((tuple._2.isInstanceOf[String] && nonEmpty(tuple._2.asInstanceOf[String])) ||
        (tuple._2.isInstanceOf[Map[_, _]] && tuple._2.asInstanceOf[Map[_, _]].nonEmpty))
  }

  def getString(properties: Map[String, _], name: String): Option[String] = {
    val value = if (properties.contains(name)) properties(name).toString else "-"
    Some(value).filter(nonEmpty)
  }

  def splitPath(pathString: String): (String, String) = {
    val pathUrl = new URI(pathString)
    (pathUrl.getPath, pathUrl.getQuery)
  }
}

case class Payload private (
  payloadType: String,
  contents: Option[String],
  reference: Option[String]
){
  require(payloadType.length < 100, "Payload type too long.")
}

object Payload {
  def apply(payloadType: String, contents: String): Payload = {
    new Payload(payloadType, Some(contents), None)
  }

  def apply(payloadType: String, reference: Option[String] = None): Payload = {
    new Payload(payloadType, None, reference)
  }
}

case class Enrolment(
  serviceName: String,
  identifiers: Map[String, String]
)
