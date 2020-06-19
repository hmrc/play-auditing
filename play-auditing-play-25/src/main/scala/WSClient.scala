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

import scala.concurrent.duration.Duration

package object handler {
  type WSClient = play.api.libs.ws.WSClient

  object WSClient {
    def apply(
      connectTimeout: Duration,
      requestTimeout: Duration,
      userAgent     : String
    ): WSClient = {
      // TODO inject these, and ApplicationLifecycle to unregister (requires dependency on play)
      implicit val system = akka.actor.ActorSystem()
      implicit val materializer = akka.stream.ActorMaterializer()

      import play.api.libs.ws.WSClientConfig
      import play.api.libs.ws.ahc.{AhcConfigBuilder, AhcWSClientConfig, AhcWSClient}
      import akka.stream.Materializer
      //import play.api.ApplicationLifecycle
      import scala.concurrent.Future

      new AhcWSClient(
        new AhcConfigBuilder(
          ahcConfig = AhcWSClientConfig()
                        .copy(wsClientConfig = WSClientConfig()
                          .copy(
                            connectionTimeout = connectTimeout,
                            requestTimeout    = requestTimeout,
                            userAgent         = Some(userAgent)

                          )
                        )
        ).build()
      )

 //* class MyService @Inject() (lifecycle: ApplicationLifecycle)(implicit mat: Materializer) {
 //   private val client = new AhcWSClient(new AhcConfigBuilder().build())
  //}
   /*lifecycle.addStopHook(() =>
 *     // Make sure you close the client after use, otherwise you'll leak threads and connections
 *     client.close()
 *     Future.successful(())
 *   }*/
    }
  }
}