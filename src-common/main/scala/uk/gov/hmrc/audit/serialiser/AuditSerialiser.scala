/*
 * Copyright 2021 HM Revenue & Customs
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

import play.api.libs.json.{JsString, JsValue, Json, Writes}
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

object DateWriter {
  // Datastream does not support default X offset (i.e. `Z` must be `+0000`)
  implicit def instantWrites = new Writes[Instant] {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

    def writes(instant: Instant): JsValue =
      JsString(dateFormat.withZone(ZoneId.of("UTC")).format(instant))
  }
}

trait AuditSerialiserLike {
  def serialise(event: DataEvent): JsValue
  def serialise(event: ExtendedDataEvent): JsValue
  def serialise(event: MergedDataEvent): JsValue
}

class AuditSerialiser extends AuditSerialiserLike {
  private implicit val dateWriter: Writes[Instant] = DateWriter.instantWrites
  private implicit val dataEventWriter: Writes[DataEvent] = Json.writes[DataEvent]
  private implicit val dataCallWriter: Writes[DataCall] = Json.writes[DataCall]
  private implicit val extendedDataEventWriter: Writes[ExtendedDataEvent] = Json.writes[ExtendedDataEvent]
  private implicit val mergedDataEventWriter: Writes[MergedDataEvent] = Json.writes[MergedDataEvent]

  override def serialise(event: DataEvent): JsValue =
    Json.toJson(event)

  override def serialise(event: ExtendedDataEvent): JsValue =
    Json.toJson(event)

  override def serialise(event: MergedDataEvent): JsValue =
    Json.toJson(event)
}

object AuditSerialiser extends AuditSerialiser
