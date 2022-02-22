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

package uk.gov.hmrc.audit.handler

import org.mockito.Mockito._
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.Logger
import play.api.libs.json.JsString

import scala.concurrent.ExecutionContext.Implicits.global

class LoggingHandlerSpec extends AnyWordSpecLike with MockitoSugar {

  val mockLog: Logger = mock[Logger]
  val loggingHandler = new LoggingHandler(mockLog)

  "LoggingHandler" should {
    "log the event" in {
      val expectedLogContent = """DS_EventMissed_AuditRequestFailure : audit item : "FAILED_EVENT""""

      loggingHandler.sendEvent(JsString("FAILED_EVENT"))

      verify(mockLog).warn(expectedLogContent)
    }
  }
}
