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

package uk.gov.hmrc.play.audit.http.connector

trait Counter {
  def inc(): Unit
}

case class DatastreamMetrics(
  successCounter: Counter,
  rejectCounter : Counter,
  failureCounter: Counter,
  metricsKey    : Option[String] // not present if metrics are disabled
)

object DatastreamMetrics {
  private case object DisabledCounter extends Counter {
    override def inc(): Unit = ()
  }

  lazy val disabled: DatastreamMetrics =
    DatastreamMetrics(
      successCounter = DisabledCounter,
      rejectCounter  = DisabledCounter,
      failureCounter = DisabledCounter,
      metricsKey     = None
    )

  def apply(prefix: String, mkCounter: String => Counter): DatastreamMetrics =
    DatastreamMetrics(
      successCounter = mkCounter("audit.success"),
      rejectCounter  = mkCounter("audit.reject"),
      failureCounter = mkCounter("audit.failure"),
      metricsKey     = Some(prefix)
    )
}
