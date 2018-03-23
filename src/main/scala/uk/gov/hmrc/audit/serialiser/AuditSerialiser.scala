/*
 * Copyright 2018 HM Revenue & Customs
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

import org.json4s.ext.JodaTimeSerializers
import org.json4s.{CustomSerializer, Extraction, Formats, JField, JNothing, JObject, JString, JValue, NoTypeHints}
import org.json4s.native.Serialization
import org.json4s.native.Serialization.write
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.hmrc.audit.model.AuditEvent
import uk.gov.hmrc.play.audit.model.{DataEvent, MergedDataEvent}

trait AuditSerialiserLike {
  def serialise(event: DataEvent): String
  def serialise(event: MergedDataEvent): String
  def serialise(event: AuditEvent): String
}

class EmptyStringPropertyTrimmingSerialiser extends CustomSerializer[String](_ => ({
  // we don't support deserialisation
  case _: JValue => ""
},{
  case x: String =>
    if (x == null || x.trim.isEmpty) JNothing else new JString(x)
}))

class EmptyMapPropertyTrimmingSerialiser extends CustomSerializer[Map[String, Any]](ser => ({
  // we don't support deserialisation
  case _: JObject => Map()
},{
  case x: Map[String, Any] =>
    val properties = x.filterNot(p => { p._1.isEmpty || p._2 == null || p._2 == "" }).map(p => new JField(p._1, Extraction.decompose(p._2)(ser))).toList
    if (properties.isEmpty) JNothing else new JObject(properties)
}))

class AuditSerialiser extends AuditSerialiserLike {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val formats: Formats = Serialization.formats(NoTypeHints).skippingEmptyValues ++
    JodaTimeSerializers.all + new EmptyMapPropertyTrimmingSerialiser + new EmptyStringPropertyTrimmingSerialiser

  override def serialise(event: DataEvent): String = {
    log.info(s"Serialise a DataEvent")
    write(event)(formats)
  }

  override def serialise(event: MergedDataEvent): String = {
    log.info(s"Serialise a MergedDataEvent")
    write(event)(formats)
  }

  override def serialise(event: AuditEvent): String = {
    log.info(s"Serialise an AuditEvent")
    write(event)(formats)
  }
}

object AuditSerialiser extends AuditSerialiser
