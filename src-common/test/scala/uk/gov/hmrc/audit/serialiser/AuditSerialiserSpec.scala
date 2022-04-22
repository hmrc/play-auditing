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

package uk.gov.hmrc.audit.serialiser

import java.time.Instant

import play.api.libs.json.{Json, JsString}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.audit.BuildInfo
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent, TruncationLog}

class AuditSerialiserSpec extends AnyWordSpecLike with Matchers {

  "AuditSerialiser" should {
    "serialise DataEvent" in {
      testDataEvent(None, "")
    }

    "serialise DataEvent with truncationLog" in {
      testDataEvent(
        truncationLog =
          Some(TruncationLog(
            truncatedFields = List("request.detail.requestdetailkey"),
            timestamp       = Instant.parse("2007-12-03T10:16:31.124Z")
          )),
        expectedTruncationJson = s""",
          "dataPipeline": {
            "truncation": {
              "truncationLog": [{
                "truncatedFields": ["request.detail.requestdetailkey"],
                "timestamp"      : "2007-12-03T10:16:31.124Z",
                "code"           : "play-auditing",
                "version"        : "${BuildInfo.version}"
              }]
            }
          }"""
      )
    }
    def testDataEvent(truncationLog: Option[TruncationLog], expectedTruncationJson: String) =
      AuditSerialiser.serialise(DataEvent(
        auditSource = "myapp",
        auditType   = "RequestReceived",
        eventId     = "cb5ebe82-cf3c-4f15-bd92-39a6baa1f929",
        tags        = Map("tagkey" -> "tagval"),
        detail      = Map("detailkey" -> "detailval"),
        generatedAt = Instant.parse("2007-12-03T10:15:30.000Z")
      )) shouldBe Json.parse("""{
        "auditSource": "myapp",
        "auditType"  : "RequestReceived",
        "eventId"    : "cb5ebe82-cf3c-4f15-bd92-39a6baa1f929",
        "tags"       : {"tagkey": "tagval"},
        "detail"     : {"detailkey": "detailval"},
        "generatedAt": "2007-12-03T10:15:30.000Z"
      }""")

    "serialise ExtendedDataEvent" in {
      testExtendedDataEvent(None, "")
    }

    "serialise ExtendedDataEvent with truncationLog" in {
      testExtendedDataEvent(
        truncationLog =
          Some(TruncationLog(
            truncatedFields = List("request.detail.requestdetailkey"),
            timestamp       = Instant.parse("2007-12-03T10:16:31.124Z")
          )),
        expectedTruncationJson = s""",
          "dataPipeline": {
            "truncation": {
              "truncationLog": [{
                "truncatedFields": ["request.detail.requestdetailkey"],
                "timestamp"      : "2007-12-03T10:16:31.124Z",
                "code"           : "play-auditing",
                "version"        : "${BuildInfo.version}"
              }]
            }
          }"""
      )
    }

    def testExtendedDataEvent(truncationLog: Option[TruncationLog], expectedTruncationJson: String) =
      AuditSerialiser.serialise(ExtendedDataEvent(
        auditSource   = "myapp",
        auditType     = "RequestReceived",
        eventId       = "cb5ebe82-cf3c-4f15-bd92-39a6baa1f929",
        tags          = Map("tagkey" -> "tagval"),
        detail        = JsString("detail"),
        generatedAt   = Instant.parse("2007-12-03T10:15:30.000Z"),
        truncationLog = truncationLog
      )) shouldBe Json.parse(s"""{
        "auditSource": "myapp",
        "auditType"  : "RequestReceived",
        "eventId"    : "cb5ebe82-cf3c-4f15-bd92-39a6baa1f929",
        "tags"       : {"tagkey": "tagval"},
        "detail"     : "detail",
        "generatedAt": "2007-12-03T10:15:30.000Z"
        $expectedTruncationJson
      }""")

    "serialise MergedDataEvent" in {
      testMergedDataEvent(None, "")
    }

    "serialise MergedDataEvent with truncationLog" in {
      testMergedDataEvent(
        truncationLog =
          Some(TruncationLog(
            truncatedFields = List("request.detail.requestdetailkey"),
            timestamp       = Instant.parse("2007-12-03T10:16:31.124Z")
          )),
        expectedTruncationJson = s""",
          "dataPipeline": {
            "truncation": {
              "truncationLog": [{
                "truncatedFields": ["request.detail.requestdetailkey"],
                "timestamp"      : "2007-12-03T10:16:31.124Z",
                "code"           : "play-auditing",
                "version"        : "${BuildInfo.version}"
              }]
            }
          }"""
      )
    }

    def testMergedDataEvent(truncationLog: Option[TruncationLog], expectedTruncationJson: String) =
      AuditSerialiser.serialise(MergedDataEvent(
        auditSource   = "myapp",
        auditType     = "RequestReceived",
        eventId       = "cb5ebe82-cf3c-4f15-bd92-39a6baa1f929",
        request       = DataCall(
                          tags   = Map("requesttagkey" -> "requesttagval"),
                          detail = Map("requestdetailkey" -> "requestdetailval"),
                          generatedAt = Instant.parse("2007-12-03T10:15:30.123Z")
                        ),
        response      = DataCall(
                          tags   = Map("responsetagkey" -> "responsetagval"),
                          detail = Map("responsedetailkey" -> "responsedetailval"),
                          generatedAt = Instant.parse("2007-12-03T10:16:31.123Z")
                        ),
        truncationLog = truncationLog
      )) shouldBe Json.parse(s"""{
        "auditSource": "myapp",
        "auditType"  : "RequestReceived",
        "eventId"    : "cb5ebe82-cf3c-4f15-bd92-39a6baa1f929",
        "request"    : {
                         "tags": {"requesttagkey": "requesttagval"},
                         "detail": {"requestdetailkey": "requestdetailval"},
                         "generatedAt": "2007-12-03T10:15:30.123Z"
                       },
        "response"   : {
                         "tags": {"responsetagkey": "responsetagval"},
                         "detail": {"responsedetailkey": "responsedetailval"},
                         "generatedAt": "2007-12-03T10:16:31.123Z"
                       }
        $expectedTruncationJson
      }""")
  }
}
