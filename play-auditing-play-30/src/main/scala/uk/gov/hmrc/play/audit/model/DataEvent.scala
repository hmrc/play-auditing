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

package uk.gov.hmrc.play.audit.model

import java.util.UUID
import java.time.Instant

import play.api.libs.json._

case class DataEvent(
  auditSource  : String,
  auditType    : String,
  eventId      : String              = UUID.randomUUID().toString,
  tags         : Map[String, String] = Map.empty,
  detail       : Map[String, String] = Map.empty,
  generatedAt  : Instant             = Instant.now(),
  truncationLog: TruncationLog       = TruncationLog.Empty,
  redactionLog : RedactionLog        = RedactionLog.Empty
)

case class ExtendedDataEvent(
  auditSource  : String,
  auditType    : String,
  eventId      : String              = UUID.randomUUID().toString,
  tags         : Map[String, String] = Map.empty,
  detail       : JsValue             = JsString(""),
  generatedAt  : Instant             = Instant.now(),
  truncationLog: TruncationLog       = TruncationLog.Empty,
  redactionLog : RedactionLog        = RedactionLog.Empty
)

case class DataCall(
  tags       : Map[String, String],
  detail     : Map[String, String],
  generatedAt: Instant
)

case class MergedDataEvent(
  auditSource  : String,
  auditType    : String,
  eventId      : String        = UUID.randomUUID().toString,
  request      : DataCall,
  response     : DataCall,
  truncationLog: TruncationLog = TruncationLog.Empty,
  redactionLog : RedactionLog  = RedactionLog.Empty
)

sealed trait TruncationLog {
  def truncatedFields: List[String]

  final def asEntry: Option[TruncationLog.Entry] =
    this match {
      case TruncationLog.Empty => None
      case entry @ TruncationLog.Entry(truncatedFields, _) =>
        if (truncatedFields.nonEmpty)
          Some(entry)
        else
          None
    }
}

object TruncationLog {

  case object Empty extends TruncationLog {
    override val truncatedFields: List[String] = List.empty
  }

  case class Entry(
    truncatedFields: List[String],
    timestamp      : Instant      = Instant.now()
  ) extends TruncationLog

  def of(truncatedFields: List[String]): TruncationLog =
    if (truncatedFields.nonEmpty)
      TruncationLog.Entry(truncatedFields)
    else
      TruncationLog.Empty
}

sealed trait RedactionLog {
  def redactedFields: List[String]
}

object RedactionLog {

  case object Empty extends RedactionLog {
    override val redactedFields: List[String] = List.empty
  }

  case class Entry(
    redactedFields: List[String],
    timestamp     : Instant      = Instant.now()
  ) extends RedactionLog

  def of(redactedFields: List[String]): RedactionLog =
    if (redactedFields.nonEmpty)
      RedactionLog.Entry(redactedFields)
    else
      RedactionLog.Empty
}
