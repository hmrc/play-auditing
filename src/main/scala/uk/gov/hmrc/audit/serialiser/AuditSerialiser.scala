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

package uk.gov.hmrc.audit.serialiser

import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsString, JsValue, Json, Writes}
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}

object DateWriter {
  implicit def dateTimeWrites = new Writes[DateTime] {
    private val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

    def writes(dt: DateTime): JsValue = JsString(dateFormat.withZone(DateTimeZone.UTC).print(dt.getMillis))
  }
}

trait AuditSerialiserLike {
  def serialise(event: DataEvent): String
  def serialise(event: ExtendedDataEvent): String
  def serialise(event: MergedDataEvent): String
}

class AuditSerialiser extends AuditSerialiserLike {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val dateWriter: Writes[DateTime] = DateWriter.dateTimeWrites
  implicit val dataEventWriter: Writes[DataEvent] = Json.writes[DataEvent]
  implicit val dataCallWriter: Writes[DataCall] = Json.writes[DataCall]
  implicit val extendedDataEventWriter: Writes[ExtendedDataEvent] = Json.writes[ExtendedDataEvent]
  implicit val mergedDataEventWriter: Writes[MergedDataEvent] = Json.writes[MergedDataEvent]

  override def serialise(event: DataEvent): String = {
    log.info(s"Serialise a DataEvent")
    Json.toJson(event).toString()
  }

  override def serialise(event: ExtendedDataEvent): String = {
    log.info(s"Serialise a ExtendedDataEvent")
    Json.toJson(event).toString()
  }

  override def serialise(event: MergedDataEvent): String = {
    log.info(s"Serialise a MergedDataEvent")
    Json.toJson(event).toString()
  }
}

object AuditSerialiser extends AuditSerialiser
