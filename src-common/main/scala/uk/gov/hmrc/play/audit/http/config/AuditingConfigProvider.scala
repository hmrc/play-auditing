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

import javax.inject.{Inject, Named, Provider}
import play.api.Configuration

// TODO why do we need a provider rather than just instanciating a single AuditConfig?
class AuditingConfigProvider @Inject()(
  configuration: Configuration,
  @Named("appName") appName: String
) extends Provider[AuditingConfig] {

  override def get(): AuditingConfig =
    if (configuration.get[Boolean]("auditing.enabled"))
      AuditingConfig(
        enabled           = true,
        consumer          = Some(
                              Consumer(
                                BaseUri(
                                  host     = configuration.get[String]("auditing.consumer.baseUri.host"),
                                  port     = configuration.get[Int]("auditing.consumer.baseUri.port"),
                                  protocol = configuration.getOptional[String]("auditing.consumer.baseUri.protocol").getOrElse("http")
                                )
                              )
                            ),
        auditSource       = appName,
        auditSentHeaders  = configuration.get[Boolean]("auditing.auditSentHeaders")
      )
    else
      AuditingConfig(
        enabled          = false,
        consumer         = None,
        auditSource      = "auditing disabled",
        auditSentHeaders = false
      )
}
