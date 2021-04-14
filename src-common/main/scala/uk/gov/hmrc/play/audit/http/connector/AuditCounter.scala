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

import akka.Done
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.play.audit.http.config.AuditingConfig

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.{ExecutionContext, Future}

trait AuditCounter {
  def auditingConfig: AuditingConfig
  def auditChannel: AuditChannel
  def auditMetrics: AuditCounterMetrics

  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  protected val instanceID = UUID.randomUUID().toString
  protected def currentTime() = Instant.now


  private[connector] val sequence = new AtomicLong(0)
  private[connector] val publishedSequence = new AtomicLong(0)
  private[connector] val finalSequence = new AtomicBoolean(false)

  if (auditingConfig.enabled) {
    auditMetrics.registerMetric("audit-count.sequence", () => sequence.get())
    auditMetrics.registerMetric("audit-count.published", () => publishedSequence.get())
    auditMetrics.registerMetric("audit-count.final", () => if (finalSequence.get()) 1 else 0)
  }

  def publish(isFinal:Boolean)(implicit ec: ExecutionContext): Future[Done] = {
    if (auditingConfig.enabled) {
      val currentSequence = sequence.get()
      val auditCount = Json.obj(
        "type" -> "audit-count",
        "auditSource" -> auditingConfig.auditSource,
        "instanceID" -> instanceID,
        "timestamp" -> timestamp(),
        "sequence" -> currentSequence,
        "isFinal" -> isFinal
      )
      publishedSequence.set(currentSequence)
      if (isFinal) {
        finalSequence.set(true)
      }
      logger.info(s"AuditCount: $auditCount")
      auditChannel.send("/write/audit", auditCount)(ec).map(_ => Done)(ec)
    } else {
      Future.successful(Done)
    }
  }

  def createMetadata():JsObject = {
    if (finalSequence.get) {
      logger.warn(s"Audit created after publication of final audit-count. This can lead to undetected audit loss.")
    }
    Json.obj(
      "metadata" -> Json.obj(
        "sendAttemptAt" -> timestamp(),
        "instanceID" -> instanceID,
        "sequence" -> sequence.incrementAndGet()
      )
    )
  }

  private def timestamp(): String = {
    DateTimeFormatter
      .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
      .withZone(ZoneId.of("UTC"))
      .format(currentTime())
  }
}
