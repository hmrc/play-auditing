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

package uk.gov.hmrc.audit

import uk.gov.hmrc.play.audit.http.connector.DefaultAuditConnector

class ReadmeExamples {
  def explicitAuditing(): Unit = {
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
    val connector = new DefaultAuditConnector {
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

    val future = connector.sendEvent(event)
    // TODO Should we describe an example of what to do with the returned future?
  }
}
