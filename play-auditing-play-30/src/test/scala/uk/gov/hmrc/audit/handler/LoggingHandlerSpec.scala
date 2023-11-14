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

package uk.gov.hmrc.audit.handler

import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.Logger
import play.api.libs.json.{Json, JsString}

import scala.concurrent.ExecutionContext.Implicits.global

class LoggingHandlerSpec extends AnyWordSpecLike with MockitoSugar with Matchers {

  val mockLog: Logger = mock[Logger]
  val loggingHandler = new LoggingHandler(mockLog)

  "LoggingHandler" should {
    "log the event" in {
      val expectedLogContent = """DS_EventMissed_AuditRequestFailure : audit item : "FAILED_EVENT""""

      loggingHandler.sendEvent(JsString("FAILED_EVENT"))

      verify(mockLog).warn(expectedLogContent)
    }

    "remove sensitive data from event" in {
      val event = Json.parse("""
      |{
      |  "auditSource": "vulnerabilities",
      |  "auditType": "OutboundCall",
      |  "eventId": "d50b2de6-07e0-46a6-9541-c8f719b32bb9",
      |  "request": {
      |    "tags": {
      |      "clientIP": "-",
      |      "X-Request-ID": "-",
      |      "deviceID": "-",
      |      "clientPort": "-",
      |      "path": "https://artefacts.tax.service.gov.uk/xray/api/v1/reports/export/836896?file_name=AppSec-report-account-context-fixer-frontend_0.11.0&format=json",
      |      "X-Session-ID": "-",
      |      "Akamai-Reputation": "-"
      |    },
      |    "detail": {
      |      "ipAddress": "-",
      |      "Authorization": "Bearer the_token"
      |    }
      |  }
      |}
      """.stripMargin)

      val eventWithNoSensitiveData = loggingHandler.removeSensitiveData(event)

      (event \ "request" \ "detail" \ "Authorization"                   ).isDefined shouldBe true
      (eventWithNoSensitiveData \ "request" \ "detail" \ "Authorization").isEmpty   shouldBe true
    }
  }
}
