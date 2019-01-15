/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.play.audit

import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.AkamaiReputation

object AuditExtensions {
  class AuditHeaderCarrier(carrier: HeaderCarrier) {
    private lazy val auditTags = Map[String, String](
      carrier.names.xRequestId -> carrier.requestId.map(_.value).getOrElse("-"),
      carrier.names.xSessionId -> carrier.sessionId.map(_.value).getOrElse("-"),
      "clientIP" -> carrier.trueClientIp.getOrElse("-"),
      "clientPort" -> carrier.trueClientPort.getOrElse("-"),
      "Akamai-Reputation" -> carrier.akamaiReputation.getOrElse(AkamaiReputation("-")).value,
      carrier.names.deviceID -> carrier.deviceID.getOrElse("-"),
      //path is not a header but http-verbs-play-25 puts it in HeaderCarrier.otherHeaders so that play-auditing can
      //get the request path without depending on play and without modifying http-core
      //Modifying http-core is hard right now because it depends on play 2.6
      "path" -> carrier.otherHeaders.collect { case ("path",value) => value }.headOption.getOrElse("-")
    )

    def toAuditTags(transactionName: String, path: String): Map[String, String] = {
      auditTags ++ Map[String, String](
        TransactionName -> transactionName,
        Path -> path
      )
    }

    def toAuditTags(path: String): Map[String, String] = auditTags + (Path -> path)

    def toAuditTags(): Map[String, String] = auditTags

    def toAuditDetails(details: (String, String)*): Map[String, String] = details.toMap

    def appendToDefaultTags(existing: Map[String, String]) = auditTags ++ existing
  }

  implicit def auditHeaderCarrier(carrier: HeaderCarrier): AuditHeaderCarrier = new AuditHeaderCarrier(carrier)
}
