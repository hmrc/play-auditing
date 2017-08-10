/*
 * Copyright 2017 HM Revenue & Customs
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

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.hmrc.audit.HandlerResult

class LoggingHandler(log: Logger) extends AuditHandler {

  private val ErrorKey = "DS_EventMissed_AuditRequestFailure"

  def makeFailureMessage(event: String): String = s"$ErrorKey : audit item : $event"

  def sendEvent(event: String): HandlerResult = {
    val message = makeFailureMessage(event)
    log.warn(message)
    HandlerResult.Success
  }
}

object LoggingHandler extends LoggingHandler(LoggerFactory.getLogger(getClass))
