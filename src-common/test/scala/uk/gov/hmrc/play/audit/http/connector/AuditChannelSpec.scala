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

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{verify => _, _}
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import play.api.libs.json.Json
import uk.gov.hmrc.audit.{DatastreamMetricsMock, WireMockUtils}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}

import scala.concurrent.ExecutionContext

class AuditChannelSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar
     with OneInstancePerTest {

  implicit val ec: ExecutionContext = RunInlineExecutionContext
  implicit val as: ActorSystem      = ActorSystem()
  implicit val m: Materializer      = ActorMaterializer()//required for play 2.6

  private def createAuditChannel(config: AuditingConfig): AuditChannel = new AuditChannel with DatastreamMetricsMock {
    override def auditingConfig: AuditingConfig = config
    override def materializer: Materializer = implicitly
    override def lifecycle: ApplicationLifecycle = new DefaultApplicationLifecycle()
    override def datastreamMetrics: DatastreamMetrics = mockDatastreamMetrics()
  }

  "AuditConnector" should {
    "post data to datastream" in {
      val testPort = WireMockUtils.availablePort
      val consumer = Consumer(BaseUri("localhost", testPort, "http"))
      val config = AuditingConfig(consumer = Some(consumer), enabled = true, auditSource = "the-project-name", auditSentHeaders = false)
      val channel = createAuditChannel(config)
      val wireMock = new WireMockServer(testPort)
      WireMock.configureFor("localhost", testPort)
      wireMock.start()

      WireMock.stubFor(
        post(urlPathEqualTo("/write/audit"))
          .withRequestBody(containing("TEST_DATA"))
          .willReturn(aResponse().withStatus(204)))

      channel.send("/write/audit", Json.obj("test" -> "TEST_DATA")).futureValue
      WireMock.verify(1, postRequestedFor(urlPathEqualTo("/write/audit")))
      WireMock.reset()

      wireMock.stop()
    }
  }
}
