/*
 * Copyright 2019 HM Revenue & Customs
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
import org.scalatest.WordSpecLike
import org.scalatest.mockito.MockitoSugar
import org.slf4j.Logger

class LoggingHandlerSpec extends WordSpecLike with MockitoSugar {

  val mockLog: Logger = mock[Logger]
  val loggingHandler = new LoggingHandler(mockLog)

  "When logging an error, the message" should {
    "Start with a known value so that downstream processing knows how to deal with it" in {
      val expectedLogContent = "DS_EventMissed_AuditRequestFailure : audit item : FAILED_EVENT"

      loggingHandler.sendEvent("FAILED_EVENT")

      verify(mockLog).warn(expectedLogContent)
    }
  }
}
