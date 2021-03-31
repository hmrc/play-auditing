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

package uk.gov.hmrc.play.audit.http.config

import scala.language.implicitConversions

case class BaseUri(
  host    : String,
  port    : Int,
  protocol: String
) {
  val uri: String =
    s"$protocol://$host:$port".stripSuffix("/") + "/"

  def addEndpoint(endpoint: String): String =
    s"$uri${endpoint.stripPrefix("/")}"
}

case class Consumer(
  baseUri            : BaseUri,
  singleEventUri     : String  = "write/audit",
  mergedEventUri     : String  = "write/audit/merged",
  largeMergedEventUri: String  = "write/audit/merged/large"
) {
  val singleEventUrl     : String = baseUri.addEndpoint(singleEventUri)
  val mergedEventUrl     : String = baseUri.addEndpoint(mergedEventUri)
  val largeMergedEventUrl: String = baseUri.addEndpoint(largeMergedEventUri)

}

object Consumer {
  implicit def baseUriToConsumer(b: BaseUri): Consumer = Consumer(b)
}

case class AuditingConfig(
  consumer   : Option[Consumer],
  enabled    : Boolean,
  auditSource: String,
  auditSentHeaders: Boolean
)
