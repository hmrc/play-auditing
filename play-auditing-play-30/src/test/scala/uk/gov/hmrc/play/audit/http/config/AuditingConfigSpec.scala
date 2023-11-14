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

package uk.gov.hmrc.play.audit.http.config

import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

class AuditingConfigSpec extends AnyWordSpec with Matchers with MockitoSugar {

  "AuditingConfig.fromConfig" should {
    "load config" in {
      val config = Configuration(
        "appName"                        -> "app-name",
        "auditing.enabled"               -> "true",
        "auditing.traceRequests"         -> "true",
        "auditing.consumer.baseUri.host" -> "localhost",
        "auditing.consumer.baseUri.port" -> "8100",
        "auditing.auditSentHeaders"      -> "false"
      )

      AuditingConfig.fromConfig(config) shouldBe AuditingConfig(
        consumer         = Some(Consumer(BaseUri("localhost", 8100, "http"))),
        enabled          = true,
        auditSource      = "app-name",
        auditSentHeaders = false
      )
    }

    "allow audit to be disabled" in {
      val config = Configuration(
        "auditing.enabled" -> "false"
      )

      AuditingConfig.fromConfig(config) shouldBe AuditingConfig(
        consumer         = None,
        enabled          = false,
        auditSource      = "auditing disabled",
        auditSentHeaders = false
      )
    }
  }
}
