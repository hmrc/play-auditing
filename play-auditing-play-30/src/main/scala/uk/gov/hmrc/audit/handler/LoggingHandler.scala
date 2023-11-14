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

import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsObject, JsValue}
import uk.gov.hmrc.audit.HandlerResult

import scala.concurrent.{ExecutionContext, Future}

class LoggingHandler(logger: Logger) extends AuditHandler {

  private val ErrorKey = "DS_EventMissed_AuditRequestFailure"

  def makeFailureMessage(event: JsValue): String =
    s"$ErrorKey : audit item : ${event.toString}"

  private val fieldsToRemove = Seq("authorization")
  private[handler] def removeSensitiveData(event: JsValue): JsValue =
    event.asOpt[JsObject].fold(event)(e => JsObject(removeSensitiveData(e)))

  private def removeSensitiveData(jsObject: JsObject): Seq[(String, JsValue)] =
    jsObject.fields.foldLeft(Seq[(String, JsValue)]()){ case (acc, (name, value)) =>
       if (fieldsToRemove.contains(name.toLowerCase))
          acc
        else
          value match {
            case o:JsObject => removeSensitiveData(o)
            case _          => acc :+ (name, value)
          }
      }

  override def sendEvent(event: JsValue)(implicit ec: ExecutionContext): Future[HandlerResult] = {
    val message = makeFailureMessage(event)
    logger.warn(message)
    Future.successful(HandlerResult.Success)
  }
}

object LoggingHandler extends LoggingHandler(LoggerFactory.getLogger(classOf[LoggingHandler]))
