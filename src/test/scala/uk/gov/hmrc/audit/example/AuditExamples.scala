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

package uk.gov.hmrc.audit.example

import org.joda.time.{DateTime, DateTimeZone}
import uk.gov.hmrc.play.audit.AuditExtensions._
import uk.gov.hmrc.audit.connector.AuditConnector
import uk.gov.hmrc.audit.model._
import uk.gov.hmrc.play.audit.model._
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext

class AuditExamples {

  implicit val ec: ExecutionContext = ExecutionContext.global

  // TODO How is this configured?
  val auditConnector: AuditConnector = AuditConnector

  val appName = "some-app"
  val auditType = "AuditType"
  val path = "/some/uri/path?foo=bar&blah=wibble"
  val method: String = "GET"

  def newStyle()(implicit hc: HeaderCarrier, auditConnector: AuditConnector): Unit = {
    // A very simple event with no extra detail fields
    auditConnector.sendEvent(AuditEvent(appName, auditType, method, path))

    // A simple event with a detail field
    auditConnector.sendEvent(AuditEvent(appName, auditType, method, path, detail = Map("myKey" -> "myValue")))

    // A complicated event with a lot of extra information
    // These should only be required if TxM asks for them specifically
    val requestHeaders: Map[String, String] = Map("User-Agent" -> "Foo")
    val identifiers: Map[String, String] = Map("credID" -> "00000001234")
    val enrolments: List[Enrolment] = List(Enrolment("IR-SA", Map("UTR" -> "1234")))
    val responseHeaders: Map[String, String] = Map("Some-Response" -> "value")
    val responseStatus: Int = 403
    // TODO Actually implement this complex example...
    auditConnector.sendEvent(AuditEvent(appName, auditType, method, path, detail = Map("myKey" -> "myValue")))
  }

  def legacyDataEvent()(implicit hc: HeaderCarrier, auditConnector: AuditConnector): Unit = {
    // A legacy DataEvent with tags and details (this will still work)
    val legacyDataEvent = DataEvent(
      auditType = "SomeAuditType",
      tags = hc.toAuditTags("some_transaction_name", path) + ("aTagField" -> "with a tag value"),
      detail = hc.toAuditDetails()
        ++ Map("aDetailField" -> "with a detail value"),
      auditSource = appName)
    auditConnector.sendEvent(legacyDataEvent)

    // The above legacy DataEvent would look like the following with the new code
    auditConnector.sendEvent(AuditEvent(appName, "SomeAuditType", method, path, detail = Map(
      "aDetailField" -> "with a detail value",
      "aTagField" -> "with a tag value"
    )))
  }

  def legacyMergedDataEvent()(implicit hc: HeaderCarrier, auditConnector: AuditConnector): Unit = {
    // A legacy MergedDataEvent with tags and details (this will still work)
    val legacyMergedDataEvent = MergedDataEvent(
      auditSource = appName,
      auditType = "SomeAuditType",
      request = DataCall(
        tags = hc.toAuditTags("some_transaction_name", path),
        detail = Map("requestBody" -> "{\"ohai\": \"gimmeh\"}"),
        generatedAt = DateTime.now(DateTimeZone.UTC)
      ),
      response = DataCall(
        tags = hc.toAuditTags("some_transaction_name", path),
        detail = Map("responseBody" -> "{\"icanhaz\": \"kthxbye\"}"),
        generatedAt = DateTime.now(DateTimeZone.UTC)
      )
    )
    auditConnector.sendEvent(legacyMergedDataEvent)

    // The above legacy MergedDataEvent would look like the following with the new code
    auditConnector.sendEvent(AuditEvent(appName, "SomeAuditType", method, path,
      requestPayload = Some(Payload("application/json", "{\"ohai\": \"gimmeh\"}")),
      responsePayload = Some(Payload("application/json", "{\"icanhaz\": \"kthxbye\"}"))))
  }

  def oldStylePlayAuditingStillWorks(): Unit = {
    import uk.gov.hmrc.http.HeaderCarrier
    import uk.gov.hmrc.play.audit.AuditExtensions._
    import uk.gov.hmrc.play.audit.http.config.AuditingConfig
    import uk.gov.hmrc.play.audit.http.connector.AuditConnector
    import uk.gov.hmrc.play.audit.model.DataEvent

    import scala.concurrent.ExecutionContext.Implicits.global

    // simplest default config (you will want to do something different)
    val config = AuditingConfig(consumer = None, enabled = true)

    // setup global objects
    val appName = "preferences"
    val connector = new AuditConnector {
      override def auditingConfig: AuditingConfig = config
    }

    // get objects relating to the current request
    val carrier = HeaderCarrier()

    // create the audit event
    val event = DataEvent(appName, "SomeEventHappened",
      tags = carrier.toAuditTags(
        transactionName = "some_series_of_events",
        path = "/some/path"
      ),
      detail = carrier.toAuditDetails(
        "someServiceSpecificKey" -> "someValue"
      ))

    connector.sendEvent(event)
  }
}
