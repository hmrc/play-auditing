/*
 * Copyright 2022 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.Inspectors
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.JsString
import play.api.libs.ws.StandaloneWSRequest
import uk.gov.hmrc.audit.WSClient

import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeoutException
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class HttpHandlerSpec
  extends AnyWordSpecLike
     with Inspectors
     with Matchers
     with ScalaFutures
     with MockitoSugar {

  trait Test {
    val wsClient = mock[WSClient]
    val httpHandler = new HttpHandler(
      endpointUrl = new URL("http", "localhost", 9999, "/some/path"),
      wsClient = wsClient
    )
  }

  "HttpHandler" should {
    class BasicThrowable(message: String) extends Throwable(message)

    val checkedPostExceptions = Seq(
      new IllegalStateException("Closed")
    )

    val uncheckedPostExceptions = Seq(
      new IOException("IO Exception"),
      new TimeoutException("Timed out"),
      new BasicThrowable("Basic Throwable")
    )

  "return failure whenever WSClient url throws" in new Test {
      val e = new IllegalArgumentException("illegal argument") // only permitted error via checked exception
      when(wsClient.url(any())).thenThrow(e)

      httpHandler.sendHttpRequest(JsString("any old thing")).futureValue mustBe HttpResult.Failure("Error opening connection or sending request (sync)", Some(e))
    }

    "return failure whenever WSClient POST throws" in forAll(checkedPostExceptions) { e =>
      new Test {
        val requestMock = mock[StandaloneWSRequest]
        when(wsClient.url(any())).thenReturn(requestMock)
        when(requestMock.post(any())(any())).thenThrow(e)

        httpHandler.sendHttpRequest(JsString("any old thing")).futureValue mustBe HttpResult.Failure("Error opening connection or sending request (sync)", Some(e))
      }
    }

    "return failure whenever WSClient POST returns failed future" in forAll(checkedPostExceptions ++ uncheckedPostExceptions) { e =>
      new Test {
        val requestMock = mock[StandaloneWSRequest]
        when(wsClient.url(any())).thenReturn(requestMock)
        when(requestMock.post(any())(any())).thenReturn(Future.failed(e))

        httpHandler.sendHttpRequest(JsString("any old thing")).futureValue mustBe HttpResult.Failure("Error opening connection or sending request (async)", Some(e))
      }
    }
  }
}
