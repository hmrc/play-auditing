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

package uk.gov.hmrc.audit.serialiser

import play.api.libs.functional.syntax._
import play.api.libs.json.{JsObject, JsValue, Json, Writes, __}
import uk.gov.hmrc.audit.BuildInfo
import uk.gov.hmrc.play.audit.model._

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

trait AuditSerialiserLike {
  def serialise(event: DataEvent): JsObject
  def serialise(event: ExtendedDataEvent): JsObject
  def serialise(event: MergedDataEvent): JsObject
}

class AuditSerialiser extends AuditSerialiserLike {
  // Datastream requires the .000 ms
  private implicit def instantWrites: Writes[Instant] =
    Writes.temporalWrites[Instant, DateTimeFormatter](
      new DateTimeFormatterBuilder().appendInstant(3).toFormatter
    )

  private implicit val truncationLogWriterEntry: Writes[TruncationLog.Entry] = {
    Writes[TruncationLog.Entry] {
      case TruncationLog.Entry(truncatedFields, timestamp) =>
        Json.obj(
          "truncationLog" -> Json.arr(Json.obj(
              "truncatedFields" -> truncatedFields,
              "timestamp" -> timestamp,
              "code" -> "play-auditing",
              "version" -> BuildInfo.version
            ))
        )
    }
  }

  private implicit val redactionLogWriter: Writes[RedactionLog] = {
    Writes[RedactionLog] {
      case RedactionLog.Empty =>
        Json.obj("containsRedactions" -> false)
      case RedactionLog.Entry(redactedFields, timestamp) =>
        Json.obj(
          "containsRedactions" -> true,
          "redactionLog" -> Json.arr(Json.obj(
            "redactedFields" -> redactedFields,
            "timestamp" -> timestamp,
            "code" -> "play-auditing",
            "version" -> BuildInfo.version))
      )
    }
  }

  private implicit val dataEventWriter: Writes[DataEvent] =
    ( (__ \ "auditSource"                ).write[String]
    ~ (__ \ "auditType"                  ).write[String]
    ~ (__ \ "eventId"                    ).write[String]
    ~ (__ \ "tags"                       ).write[Map[String, String]]
    ~ (__ \ "detail"                     ).write[Map[String, String]]
    ~ (__ \ "generatedAt"                ).write[Instant]
    ~ (__ \ "dataPipeline" \ "truncation").writeNullable[TruncationLog.Entry].contramap[TruncationLog](_.asEntry)
    ~ (__ \ "dataPipeline" \ "redaction" ).write[RedactionLog]
    )(unlift(DataEvent.unapply))

  private implicit val extendedDataEventWriter: Writes[ExtendedDataEvent] =
    ( (__ \ "auditSource"                ).write[String]
    ~ (__ \ "auditType"                  ).write[String]
    ~ (__ \ "eventId"                    ).write[String]
    ~ (__ \ "tags"                       ).write[Map[String, String]]
    ~ (__ \ "detail"                     ).write[JsValue]
    ~ (__ \ "generatedAt"                ).write[Instant]
    ~ (__ \ "dataPipeline" \ "truncation").writeNullable[TruncationLog.Entry].contramap[TruncationLog](_.asEntry)
    ~ (__ \ "dataPipeline" \ "redaction" ).write[RedactionLog]
    )(unlift(ExtendedDataEvent.unapply))

  private implicit val dataCallWriter: Writes[DataCall] =
    ( (__ \ "tags"       ).write[Map[String, String]]
    ~ (__ \ "detail"     ).write[Map[String, String]]
    ~ (__ \ "generatedAt").write[Instant]
    )(unlift(DataCall.unapply))

  private implicit val mergedDataEventWriter  : Writes[MergedDataEvent]   =
    ( (__ \ "auditSource"                ).write[String]
    ~ (__ \ "auditType"                  ).write[String]
    ~ (__ \ "eventId"                    ).write[String]
    ~ (__ \ "request"                    ).write[DataCall]
    ~ (__ \ "response"                   ).write[DataCall]
    ~ (__ \ "dataPipeline" \ "truncation").writeNullable[TruncationLog.Entry].contramap[TruncationLog](_.asEntry)
    ~ (__ \ "dataPipeline" \ "redaction" ).write[RedactionLog]
    )(unlift(MergedDataEvent.unapply))


  override def serialise(event: DataEvent): JsObject =
    Json.toJson(event).as[JsObject]

  override def serialise(event: ExtendedDataEvent): JsObject =
    Json.toJson(event).as[JsObject]

  override def serialise(event: MergedDataEvent): JsObject =
    Json.toJson(event).as[JsObject]
}

object AuditSerialiser extends AuditSerialiser
