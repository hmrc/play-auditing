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

package uk.gov.hmrc.audit.serialiser

import java.util.UUID

import org.joda.time.DateTime
import org.specs2.mutable.Specification
import uk.gov.hmrc.audit.model.{AuditEvent, Enrolment, Payload}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, MergedDataEvent}

class AuditSerialiserSpec extends Specification {

  val serialiser = new AuditSerialiser
  val uuid: String = UUID.randomUUID().toString
  val dateTime: DateTime = DateTime.parse("2017-01-01T12:00+00:00")
  val dateString: String = "2017-01-01T12:00:00.000Z"

  "When serialising a DataEvent the result" should {
    "Populate all supplied fields in the correct format" in {
      val dataEvent = DataEvent("source", "type", uuid, Map(("foo", "bar")), Map(("one", "two")), dateTime)
      val expectedResult = s"""{"auditSource":"source","auditType":"type","eventId":"$uuid","tags":{"foo":"bar"},"detail":{"one":"two"},"generatedAt":"$dateString"}"""
      serialiser.serialise(dataEvent) must be equalTo expectedResult
    }

    "Omit any empty field names" in {
      val dataEvent = DataEvent("source", "type", uuid, Map(("foo", "bar"), ("", "blah")), Map(("one", "two"), ("", "three")), dateTime)
      val expectedResult = s"""{"auditSource":"source","auditType":"type","eventId":"$uuid","tags":{"foo":"bar"},"detail":{"one":"two"},"generatedAt":"$dateString"}"""
      serialiser.serialise(dataEvent) must be equalTo expectedResult
    }

    "Omit any empty tag / detail field values" in {
      val dataEvent = DataEvent("source", "type", uuid, Map(("foo", "bar"), ("blah", "")), Map(("one", "two"), ("three", "")), dateTime)
      val expectedResult = s"""{"auditSource":"source","auditType":"type","eventId":"$uuid","tags":{"foo":"bar"},"detail":{"one":"two"},"generatedAt":"$dateString"}"""
      serialiser.serialise(dataEvent) must be equalTo expectedResult
    }

    "Omit any empty top level field values" in {
      val dataEvent = DataEvent("source", "", uuid, generatedAt = dateTime)
      val expectedResult = s"""{"auditSource":"source","eventId":"$uuid","generatedAt":"$dateString"}"""
      serialiser.serialise(dataEvent) must be equalTo expectedResult
    }

    "Omit any null field values" in {
      val dataEvent = DataEvent("source", "type", uuid, Map(("foo", "bar"), ("blah", null)), Map(("one", "two"), ("three", null)), dateTime)
      val expectedResult = s"""{"auditSource":"source","auditType":"type","eventId":"$uuid","tags":{"foo":"bar"},"detail":{"one":"two"},"generatedAt":"$dateString"}"""
      serialiser.serialise(dataEvent) must be equalTo expectedResult
    }

    "Omit any objects that have no fields" in {
      val dataEvent = DataEvent("source", "type", uuid, generatedAt = dateTime)
      val expectedResult = s"""{"auditSource":"source","auditType":"type","eventId":"$uuid","generatedAt":"$dateString"}"""
      serialiser.serialise(dataEvent) must be equalTo expectedResult
    }
  }

  "When serialising a MergedDataEvent the result" should {
    "Populate all supplied fields in the correct format" in {
      val requestDataCall = DataCall(Map[String, String](("foo", "bar")), Map[String, String](("one", "two")), dateTime)
      val responseDataCall = DataCall(Map[String, String](("blah", "baz")), Map[String, String](("three", "four")), dateTime)
      val mergedEvent = MergedDataEvent("source", "type", uuid, requestDataCall, responseDataCall)
      val expectedResult = s"""{"auditSource":"source","auditType":"type","eventId":"$uuid","request":{"tags":{"foo":"bar"},"detail":{"one":"two"},"generatedAt":"$dateString"},"response":{"tags":{"blah":"baz"},"detail":{"three":"four"},"generatedAt":"$dateString"}}"""
      serialiser.serialise(mergedEvent) must be equalTo expectedResult
    }
  }

  "When serialising a AuditEvent the result" should {
    "Populate all supplied fields in the correct format" in {
      implicit val hc: HeaderCarrier = HeaderCarrier()
      val detail = Map(("foo", Map("bar" -> "wibble")))
      val identifiers = Some(Map("credId" -> "0000000906547378"))
      val enrolments = Some(List(Enrolment("IR-SA", Map("sautr" -> "1278034448"))))
      val requestHeaders = Some(Map("X-Forwarded-For" -> "10.1.2.3"))
      val fields = Some(Map("email" -> "david@hoff.com"))
      val cookies = Some(Map("mdtp" -> "random value \\//\":'$"))
      val requestPayload = Some(Payload("application/json"))
      val responseHeaders = Some(Map("Cache-Control" -> "no-cache"))
      val responsePayload = Some(Payload("application/json"))
      val clientHeaders = Some(Map("Authorization" -> "123456"))
      val auditEvent = AuditEvent("source", "type", "GET", "/some/path", dateTime,
        detail, identifiers, enrolments, requestHeaders, fields, cookies,
        requestPayload, responseHeaders, responsePayload, Some(200),
        Some("1.2.3.4"), Some(1234), Some("4.3.2.1"), Some(uuid), Some(uuid),
        Some("Bearer abcdef"), clientHeaders, eventID = uuid)
      val expectedResult = s"""{"auditSource":"source","auditType":"type","generatedAt":"$dateString","eventID":"$uuid","requestID":"$uuid","sessionID":"$uuid","path":"/some/path","method":"GET","clientIP":"1.2.3.4","clientPort":1234,"receivingIP":"4.3.2.1","authorisationToken":"Bearer abcdef","clientHeaders":{"Authorization":"123456"},"requestHeaders":{"X-Forwarded-For":"10.1.2.3"},"cookies":{"mdtp":"random value \\\\//\\":'$$"},"fields":{"email":"david@hoff.com"},"requestPayload":{"payloadType":"application/json"},"identifiers":{"credId":"0000000906547378"},"enrolments":[{"serviceName":"IR-SA","identifiers":{"sautr":"1278034448"}}],"detail":{"foo":{"bar":"wibble"}},"responseHeaders":{"Cache-Control":"no-cache"},"responseStatus":200,"responsePayload":{"payloadType":"application/json"},"version":1}"""
      serialiser.serialise(auditEvent) must be equalTo expectedResult
    }
  }
}
