/*
 * Copyright 2020 HM Revenue & Customs
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

import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}

trait AuditSerialiserLike {
  def serialise(event: DataEvent): String
  def serialise(event: ExtendedDataEvent): String
  def serialise(event: MergedDataEvent): String
}

class AuditSerialiser extends AuditSerialiserLike {
  private implicit val dataEventWriter: Writes[DataEvent] = Json.writes[DataEvent]
  private implicit val dataCallWriter: Writes[DataCall] = Json.writes[DataCall]
  private implicit val extendedDataEventWriter: Writes[ExtendedDataEvent] = Json.writes[ExtendedDataEvent]
  private implicit val mergedDataEventWriter: Writes[MergedDataEvent] = Json.writes[MergedDataEvent]

  override def serialise(event: DataEvent): String =
    Json.toJson(event).toString()

  override def serialise(event: ExtendedDataEvent): String =
    Json.toJson(event).toString()

  override def serialise(event: MergedDataEvent): String =
    Json.toJson(event).toString()
}

object AuditSerialiser extends AuditSerialiser
