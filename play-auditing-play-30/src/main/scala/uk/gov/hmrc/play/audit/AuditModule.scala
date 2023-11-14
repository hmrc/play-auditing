/*
 * Copyright 2023 HM Revenue & Customs
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

import org.apache.pekko.stream.Materializer
import play.api.{Configuration, Environment}
import play.api.inject.{ApplicationLifecycle, Binding, Module}
import _root_.uk.gov.hmrc.play.audit.http.config.AuditingConfig
import _root_.uk.gov.hmrc.play.audit.http.connector.{AuditChannel, AuditConnector, DatastreamMetrics}

import javax.inject.{Inject, Singleton}


class AuditModule extends Module {
  override def bindings(
    environment  : Environment,
    configuration: Configuration
  ): Seq[Binding[_]] = Seq(
    bind[AuditChannel].to[DefaultAuditChannel],
    bind[AuditConnector].to[DefaultAuditConnector],
    bind[AuditingConfig].to(AuditingConfig.fromConfig(configuration))
  )
}

@Singleton
class DefaultAuditChannel @Inject()(
  val auditingConfig   : AuditingConfig,
  val materializer     : Materializer,
  val lifecycle        : ApplicationLifecycle,
  val datastreamMetrics: DatastreamMetrics
) extends AuditChannel

@Singleton
class DefaultAuditConnector @Inject()(
  val auditingConfig   : AuditingConfig,
  val auditChannel     : AuditChannel,
  val lifecycle        : ApplicationLifecycle,
  val datastreamMetrics: DatastreamMetrics
) extends AuditConnector
