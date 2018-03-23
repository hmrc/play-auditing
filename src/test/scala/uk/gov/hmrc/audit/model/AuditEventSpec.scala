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

import java.util.UUID

import org.joda.time.DateTime
import org.specs2.execute.Result
import org.specs2.mutable.Specification
import uk.gov.hmrc.audit.model.FieldNames._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HeaderNames
import uk.gov.hmrc.http.logging._

class AuditEventSpec extends Specification {

  val requestID: String = UUID.randomUUID.toString
  val sessionID: String = UUID.randomUUID.toString
  val auditSource: String = "source"
  val auditType: String = "type"
  val path: String = "/some/path"
  val method: String = "GET"
  val dateTime: DateTime = DateTime.parse("2017-01-01T12:00+00:00")
  val authorisationToken: String = "Bearer 451b7fdf22f2e37e14c42728f1758e35"
  val queryString: String = "foo=bar&blah=wibble"
  val body = "{\"ohai\":\"icanhazjsonbody?\"}"

  implicit val hc: HeaderCarrier = HeaderCarrier(
    requestId = Some(RequestId(requestID)),
    sessionId = Some(SessionId(sessionID)),
    authorization = Some(Authorization(authorisationToken))
  )

  "An audit event" should {
    "Contain the basic fields" in {
      val event: AuditEvent = AuditEvent(auditSource, auditType, method, path)
      event.auditSource mustEqual auditSource
      event.auditType mustEqual auditType
      event.path mustEqual path
      event.method mustEqual method
      event.generatedAt.isBeforeNow must beTrue
      event.eventID must beMatching("[a-z0-9-]+")
    }

    "Contain the request ID from the header carrier" in {
      val event = AuditEvent(auditSource, auditType, method, path)
      event.requestID mustEqual requestID
    }

    "Contain the session ID from the header carrier" in {
      val event = AuditEvent(auditSource, auditType, method, path)
      event.sessionID.get mustEqual sessionID
    }

    "Split the path and querystring if they are supplied as one" in {
      val event = AuditEvent(auditSource, auditType, method, s"$path?$queryString")
      event.path mustEqual path
      event.queryString must beSome(queryString)
    }

    "Not populate the querystring if the path ends in question mark" in {
      val event = AuditEvent(auditSource, auditType, method, s"$path?")
      event.path mustEqual path
      event.queryString must beNone
    }

    "Contain the authorisationToken from the header carrier" in {
      val event = AuditEvent(auditSource, auditType, method, path)
      event.authorisationToken must beSome(authorisationToken)
    }

    "Contain request headers from the header carrier, and from explicit request headers" in {
      val carrier = HeaderCarrier(
        requestId = Some(RequestId(requestID)),
        forwarded = Some(ForwardedFor("forwardedValue")),
        akamaiReputation = Some(AkamaiReputation("akamaiValue")),
        otherHeaders = Seq("header1" -> "value1")
      )

      val event = AuditEvent(auditSource, auditType, method, path, requestHeaders = Some(Map(
        "header2" -> "value2"
      )))(carrier)
      event.requestHeaders.get("header1") mustEqual "value1"
      event.requestHeaders.get("header2") mustEqual "value2"
      event.requestHeaders.get(HeaderNames.akamaiReputation) mustEqual "akamaiValue"
      event.requestHeaders.get(HeaderNames.xForwardedFor) mustEqual "forwardedValue"
      event.requestHeaders.get.size mustEqual 4
    }

    "Extract IP and port from header carrier" in {
      val carrier = HeaderCarrier(
        requestId = Some(RequestId(requestID)),
        trueClientIp = Some("10.1.2.3"),
        trueClientPort = Some("12345")
      )

      val event = AuditEvent(auditSource, auditType, method, path)(carrier)
      event.clientIP.get mustEqual "10.1.2.3"
      event.clientPort.get mustEqual 12345
      event.requestHeaders must beNone
    }

    "Ignores empty request headers from the header carrier" in {
      val carrier = HeaderCarrier(
        requestId = Some(RequestId(requestID)),
        forwarded = Some(ForwardedFor("")),
        akamaiReputation = Some(AkamaiReputation("")),
        otherHeaders = Seq(
          "header1" -> "",
          "header2" -> "-",
          "" -> "emptyKey1")
      )

      val event = AuditEvent(auditSource, auditType, method, path, requestHeaders = Some(Map(
        "header3" -> "",
        "header4" -> "-",
        "" -> "emptyKey2"
      )))(carrier)
      event.requestHeaders must beNone
    }

    "Contain payloads if they were supplied" in {
      val requestPayload = Some(Payload("application/json", "{\"ohai\": \"gimmeh\"}"))
      val responsePayload = Some(Payload("application/json", "{\"icanhaz\": \"kthxbye\"}"))
      val event = AuditEvent(auditSource, auditType, method, path,
        requestPayload = requestPayload,
        responsePayload = responsePayload
      )
      event.requestPayload mustEqual requestPayload
      event.responsePayload mustEqual responsePayload
    }

    "Contain identifiers if they were supplied" in {
      val identifiers = Some(Map("id1" -> "value1"))
      val event = AuditEvent(auditSource, auditType, method, path, identifiers = identifiers)
      event.identifiers.get("id1") mustEqual "value1"
      event.identifiers.get.size mustEqual 1
    }

    "Not contain empty identifiers if they were supplied" in {
      val identifiers = Some(Map(
        "" -> "value1",
        "id1" -> "",
        "id2" -> "-"))
      val event = AuditEvent(auditSource, auditType, method, path, identifiers = identifiers)
      event.identifiers must beNone
    }

    "Contain enrolments if they were supplied" in {
      val enrolments = Some(List(Enrolment("IR-SERVICE", Map("id1" -> "value1"))))
      val event = AuditEvent(auditSource, auditType, method, path, enrolments = enrolments)
      event.enrolments mustEqual enrolments
    }

    "Contain detail fields if they were supplied" in {
      val suppliedDetail = Map("detail1" -> "value1")
      val event = AuditEvent(auditSource, auditType, method, path, detail = suppliedDetail)
      event.detail.get mustEqual suppliedDetail
    }

    "Allow nested detail fields" in {
      val nestedDetail = Map("detailGroup1" -> Map("detailKey1" -> "value1"))
      val event = AuditEvent(auditSource, auditType, method, path, detail = nestedDetail)
      event.detail.get mustEqual nestedDetail
    }

    "Not contain a detail value if there were no fields populated" in {
      val suppliedDetail = Map(
        "" -> "value1",
        "supplied1" -> "",
        "supplied2" -> "-",
        "nested1" -> Map())
      val event = AuditEvent(auditSource, auditType, method, path, detail = suppliedDetail)
      event.detail must beNone
    }

    "Redirect identifiers from the detail section if they were supplied" in {
      Result.foreach(detailsToIdentifiers.keys.toSeq) { key => {
        val mappedIdentifierKey = detailsToIdentifiers(key)
        val detailWithId = Map(key -> s"${key}value1")
        val event = AuditEvent(auditSource, auditType, method, path, detail = detailWithId)
        event.detail must beEmpty
        event.identifiers.get(mappedIdentifierKey) mustEqual s"${key}value1"
      }
      }
    }

    "Contain responseHeaders if they were supplied" in {
      val responseHeaders = Some(Map("header1" -> "value1"))
      val event = AuditEvent(auditSource, auditType, method, path, responseHeaders = responseHeaders)
      event.responseHeaders mustEqual responseHeaders
    }

    "Contain responseStatus if it was supplied" in {
      val event = AuditEvent(auditSource, auditType, method, path, responseStatus = Some(666))
      event.responseStatus.get mustEqual 666
    }
  }

  "An audit event with legacy detail properties" should {
    "Extract \"method\" from a detail field if it was not supplied separately" in {
      val suppliedDetail = Map(LegacyDetailNames.method -> "GET")
      val event = AuditEvent(auditSource, auditType, "", path, detail = suppliedDetail)
      event.method mustEqual "GET"
    }

    "Extract \"statusCode\" from a detail field if it was not supplied separately" in {
      val suppliedDetail = Map(LegacyDetailNames.statusCode -> "200")
      val event = AuditEvent(auditSource, auditType, method, path, detail = suppliedDetail)
      event.responseStatus.get mustEqual 200
    }

    "Extract \"requestBody\" from a detail field if it was not supplied separately" in {
      val suppliedDetail = Map(LegacyDetailNames.requestBody -> body)
      val event = AuditEvent(auditSource, auditType, method, path, detail = suppliedDetail)
      event.requestPayload must beSome[Payload]
      event.requestPayload.get.contents.get mustEqual body
      event.requestPayload.get.reference must beNone
      // TODO How would this be done without parsing the body?
//      event.requestPayload.get.payloadType mustEqual "application/json"
    }

    "Extract \"responseMessage\" from a detail field if it was not supplied separately" in {
      val suppliedDetail = Map(LegacyDetailNames.responseMessage -> body)
      val event = AuditEvent(auditSource, auditType, method, path, detail = suppliedDetail)
      event.responsePayload must beSome[Payload]
      event.responsePayload.get.contents.get mustEqual body
      event.responsePayload.get.reference must beNone
      // TODO How would this be done without parsing the body?
//      event.responsePayload.get.payloadType mustEqual "application/json"
    }

    "Extract \"queryString\" from a detail field if it was not supplied separately" in {
      val suppliedDetail = Map(LegacyDetailNames.queryString -> queryString)
      val event = AuditEvent(auditSource, auditType, method, path, detail = suppliedDetail)
      event.queryString.get mustEqual queryString
    }

    "Extract \"Authorization\" from a detail field if it was not supplied separately" in {
      val carrier = HeaderCarrier(
        requestId = Some(RequestId(requestID))
      )

      val suppliedDetail = Map(LegacyDetailNames.authorisation -> authorisationToken)
      val event = AuditEvent(auditSource, auditType, method, path, detail = suppliedDetail)(carrier)
      event.authorisationToken.get mustEqual authorisationToken
    }
  }

  // TODO More testing of different combinations
  // TODO A test with all parameters supplied
}
