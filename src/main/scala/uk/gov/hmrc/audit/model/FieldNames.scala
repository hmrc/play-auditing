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

package uk.gov.hmrc.audit.model

object FieldNames {
  /*
   * Determines whether request headers should be in "clientHeaders" (if not
   * prefixed by a value in this list) or "requestHeaders" (if matching a prefix
   * in this list).
   */
  val requestHeaderPrefixes = Seq("X-", "Forwarded", "Akamai-", "Via", "Surrogate")

  /*
   * Any headers that should never be recorded.
   */
  val excludedRequestHeaders: Seq[String] = Seq(
  )

  /*
   * Rewrites "detail" entries (where the name matches this map key) into the
   * "identifiers" collection (where the name is taken from this map value).
   */
  val detailsToIdentifiers: Map[String, String] = Map(
    "credId" -> "credID"
  )

  /*
   * Detail fields that are handled in a special way for backward compatibility.
   */
  object LegacyDetailNames {
    val akamaiReputation = "Akamai-Reputation" // Needed?

    val method = "method"
    val statusCode = "statusCode"
    val requestBody = "requestBody"
    val responseMessage = "responseMessage"
    val queryString = "queryString"
    val referrer = "referrer"
    val authorisation = "Authorization"
  }
}
