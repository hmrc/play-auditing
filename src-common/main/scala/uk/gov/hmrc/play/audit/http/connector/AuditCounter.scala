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
import akka.actor.{ActorSystem, CoordinatedShutdown}
import play.api.libs.json.{JsObject, Json}
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import org.slf4j.{LoggerFactory, Logger}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

trait AuditCounter {
  def createMetadata():JsObject
}

private[connector] trait UnpublishedAuditCounter extends AuditCounter {
  def auditingConfig: AuditingConfig
  def auditChannel: AuditChannel
  def auditMetrics: AuditCounterMetrics

  protected val logger: Logger = LoggerFactory.getLogger("auditCounter")
  private val instanceID = UUID.randomUUID().toString
  private val sequence = new AtomicLong(0)
  private val publishedSequence = new AtomicLong(0)
  private val finalSequence = new AtomicBoolean(false)
  
  if (auditingConfig.enabled) {
    auditMetrics.registerMetric("audit-counter.sequence", () => sequence.get())
    auditMetrics.registerMetric("audit-counter.published", () => publishedSequence.get())
    auditMetrics.registerMetric("audit-counter.isFinal", () => if (finalSequence.get()) 1 else 0)
  }

  def publish(isFinal:Boolean)(implicit ec: ExecutionContext): Future[Done] = {
    if (auditingConfig.enabled) {
      val currentSequence = sequence.get()
      val auditCount = Json.obj(
        "type" -> "audit-counter",
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
      logger.info(s"AuditCounter: $auditCount")
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

  protected def currentTime() = Instant.now
  private def timestamp(): String = {
    DateTimeFormatter
      .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
      .withZone(ZoneId.of("UTC"))
      .format(currentTime())
  }
}



abstract class PublishedAuditCounter(
    actorSystem: ActorSystem,
    coordinatedShutdown: CoordinatedShutdown
  )(implicit val ec: ExecutionContext) extends UnpublishedAuditCounter {

  private val scheduler = actorSystem.scheduler.schedule(60.seconds, 60.seconds, new Runnable {
    override def run(): Unit = publish(isFinal = false)
  })

  //This is intentionally run at ServiceRequestsDone which is before the default ApplicationLifecycle stopHook
  // as this must be run before the AuditChannel WSClient is closed
  // and before final metrics report, triggered by the close in the EnabledGraphiteReporting stopHook
  coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "final audit counters") { () =>
    scheduler.cancel()
    publish(isFinal = true)
  }
}
