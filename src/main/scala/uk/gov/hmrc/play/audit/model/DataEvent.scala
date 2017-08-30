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

package uk.gov.hmrc.play.audit.model

import java.util.UUID

import org.joda.time.DateTime
import play.api.libs.json._
import uk.gov.hmrc.time.DateTimeUtils

case class DataEvent(auditSource: String,
                     auditType: String,
                     eventId: String = UUID.randomUUID().toString,
                     tags: Map[String, String] = Map.empty,
                     detail: Map[String, String] = Map.empty,
                     generatedAt: DateTime = DateTimeUtils.now)

@deprecated("This class will be removed soon. All audit event classes will be " +
  "merged, and this merged class will support all use cases (including nested details).", "3.0.0")
case class ExtendedDataEvent(auditSource: String,
                             auditType: String,
                             eventId: String = UUID.randomUUID().toString,
                             tags: Map[String, String] = Map.empty,
                             detail: JsValue = JsString(""),
                             generatedAt: DateTime = DateTimeUtils.now)

case class DataCall(tags: Map[String, String],
                    detail: Map[String, String],
                    generatedAt: DateTime)

case class MergedDataEvent(auditSource: String,
                           auditType: String,
                           eventId: String = UUID.randomUUID().toString,
                           request: DataCall,
                           response: DataCall)
