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

import play.api.libs.functional.syntax._
import play.api.libs.json.{JsObject, JsString, JsValue, Json, Writes, __}
import uk.gov.hmrc.audit.BuildInfo
import uk.gov.hmrc.play.audit.model._
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

object DateWriter {
  // TODO this looks unnecessary now
  // Datastream does not support default X offset (i.e. `Z` must be `+0000`)
  implicit def instantWrites = new Writes[Instant] {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

    def writes(instant: Instant): JsValue =
      JsString(dateFormat.withZone(ZoneId.of("UTC")).format(instant))
  }
}

trait AuditSerialiserLike {
  def serialise(event: DataEvent): JsObject
  def serialise(event: ExtendedDataEvent): JsObject
  def serialise(event: MergedDataEvent): JsObject
}

class AuditSerialiser extends AuditSerialiserLike {
  private implicit val dateWriter: Writes[Instant] =
    DateWriter.instantWrites

  private implicit val truncationLogWriter: Writes[TruncationLog] =
    ( (__ \ "truncatedFields").write[List[String]]
    ~ (__ \ "timestamp"      ).write[Instant]
    )(unlift(TruncationLog.unapply))
      .transform { (js: JsObject) =>
          js +
          ("code"      -> JsString("play-auditing")) +
          ("version"   -> JsString(BuildInfo.version)) //+
          //("timestamp" -> dateWriter.writes(Instant.now)) // or makes it hard to test?
      }

  private implicit val dataEventWriter: Writes[DataEvent] =
    ( (__ \ "auditSource"                                  ).write[String]
    ~ (__ \ "auditType"                                    ).write[String]
    ~ (__ \ "eventId"                                      ).write[String]
    ~ (__ \ "tags"                                         ).write[Map[String, String]]
    ~ (__ \ "detail"                                       ).write[Map[String, String]]
    ~ (__ \ "generatedAt"                                  ).write[Instant]
    ~ (__ \ "dataPipeline" \ "truncation" \ "truncationLog").writeNullable[List[TruncationLog]]
                                                            .contramap[Option[TruncationLog]](_.filterNot(_.truncatedFields.isEmpty).map(List(_)))
    )(unlift(DataEvent.unapply))

  private implicit val dataCallWriter: Writes[DataCall] =
    ( (__ \ "tags"       ).write[Map[String, String]]
    ~ (__ \ "detail"     ).write[Map[String, String]]
    ~ (__ \ "generatedAt").write[Instant]
    )(unlift(DataCall.unapply))

  private implicit val extendedDataEventWriter: Writes[ExtendedDataEvent] =
    ( (__ \ "auditSource"                                  ).write[String]
    ~ (__ \ "auditType"                                    ).write[String]
    ~ (__ \ "eventId"                                      ).write[String]
    ~ (__ \ "tags"                                         ).write[Map[String, String]]
    ~ (__ \ "detail"                                       ).write[JsValue]
    ~ (__ \ "generatedAt"                                  ).write[Instant]
    ~ (__ \ "dataPipeline" \ "truncation" \ "truncationLog").writeNullable[List[TruncationLog]]
                                                            .contramap[Option[TruncationLog]](_.filterNot(_.truncatedFields.isEmpty).map(List(_)))
    )(unlift(ExtendedDataEvent.unapply))

  private implicit val mergedDataEventWriter  : Writes[MergedDataEvent]   =
    ( (__ \ "auditSource"                                  ).write[String]
    ~ (__ \ "auditType"                                    ).write[String]
    ~ (__ \ "eventId"                                      ).write[String]
    ~ (__ \ "request"                                      ).write[DataCall]
    ~ (__ \ "response"                                     ).write[DataCall]
    ~ (__ \ "dataPipeline" \ "truncation" \ "truncationLog").writeNullable[List[TruncationLog]]
                                                            .contramap[Option[TruncationLog]](_.filterNot(_.truncatedFields.isEmpty).map(List(_)))
    )(unlift(MergedDataEvent.unapply))


  override def serialise(event: DataEvent): JsObject =
    Json.toJson(event).as[JsObject]

  override def serialise(event: ExtendedDataEvent): JsObject =
    Json.toJson(event).as[JsObject]

  override def serialise(event: MergedDataEvent): JsObject =
    Json.toJson(event).as[JsObject]
}

object AuditSerialiser extends AuditSerialiser
