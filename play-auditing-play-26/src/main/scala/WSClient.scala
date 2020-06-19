/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.audit

import akka.stream.Materializer
import play.api.libs.ws.ahc.{StandaloneAhcWSClient, AhcWSClientConfigFactory}
import play.api.libs.ws.WSClientConfig

import scala.concurrent.duration.Duration

package object handler {
  type WSClient = play.api.libs.ws.StandaloneWSClient

  object WSClient {
    def apply(
      connectTimeout: Duration,
      requestTimeout: Duration,
      userAgent     : String
    )(implicit
      materializer: Materializer
    ): WSClient =
      StandaloneAhcWSClient(
        config = AhcWSClientConfigFactory.forConfig()
                   .copy(wsClientConfig = WSClientConfig()
                     .copy(
                       connectionTimeout = connectTimeout,
                       requestTimeout    = requestTimeout,
                       userAgent         = Some(userAgent)
                     )
                   )
      )
  }
}
