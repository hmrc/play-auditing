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

package uk.gov.hmrc.play.audit.http.connector

import com.codahale.metrics.Counter
import com.kenshoo.play.metrics._

case class DatastreamMetrics(
  successCounter: Counter,
  rejectedCounter: Counter,
  failureCounter: Counter
)

object DatastreamMetrics {
  def register(metrics: Metrics) = {
    DatastreamMetrics(
      metrics.defaultRegistry.counter("audit.success"),
      metrics.defaultRegistry.counter("audit.rejected"),
      metrics.defaultRegistry.counter("audit.failure")
    )
  }
}