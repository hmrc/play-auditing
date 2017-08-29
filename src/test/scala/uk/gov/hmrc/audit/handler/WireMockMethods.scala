/*
 * Copyright 2017 HM Revenue & Customs
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

package uk.gov.hmrc.audit.handler

import java.io.IOException
import java.net.ServerSocket

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

trait WireMockMethods {

  var wireMock: WireMockServer = _
  var wireMockPort: Int = availablePort

  def availablePort: Int = {
    var port = 9876
    var socket: ServerSocket = null

    try {
      socket = new ServerSocket(0)
      port = socket.getLocalPort
    } catch {
      case ex: IOException =>
    } finally {
      if (socket != null) {
        try {
          socket.close()
        } catch {
          case ex: IOException =>
        }
      }
    }

    port
  }

  def startWireMock(): Unit = {
    wireMock = new WireMockServer(wireMockPort)
    WireMock.configureFor("localhost", wireMockPort)
    wireMock.start()
  }

  def stopWireMock(): Unit = {
    wireMock.stop()
  }
}

trait WireMockTestSuite extends WireMockMethods with BeforeAndAfterEach with BeforeAndAfterAll {
  this: Suite =>

  override def beforeAll: Unit = {
    startWireMock()
  }

  override def afterAll: Unit = {
    stopWireMock()
  }

  override def afterEach: Unit = {
    WireMock.reset()
  }
}
