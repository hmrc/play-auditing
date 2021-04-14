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

import akka.actor.{ActorSystem, CoordinatedShutdown}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait AuditCountPublisher {
  def actorSystem: ActorSystem
  def coordinatedShutdown : CoordinatedShutdown
  implicit def ec: ExecutionContext
  def auditCounter: AuditCounter

  val scheduler = actorSystem.scheduler.schedule(60.seconds, 60.seconds, new Runnable {
    override def run(): Unit = auditCounter.publish(false)
  })

  //This is intentionally run at ServiceRequestsDone which is before the default ApplicationLifecycle stopHook
  // as this must be run before the AuditChannel WSClient is closed
  // and before final metrics report, triggered by the close in the EnabledGraphiteReporting stopHook
  coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "final audit counters") { () =>
    scheduler.cancel()
    auditCounter.publish(isFinal = true)
  }

}
