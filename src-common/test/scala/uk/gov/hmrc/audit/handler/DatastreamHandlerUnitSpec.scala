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

package uk.gov.hmrc.audit.handler

import org.mockito.Mockito.{times, verify, verifyNoInteractions}
import org.scalatest.Inspectors
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.Logger
import play.api.libs.json.{JsString, JsValue}
import uk.gov.hmrc.audit.{DatastreamMetricsMock, HandlerResult, WSClient}

import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global

class DatastreamHandlerUnitSpec
  extends AnyWordSpecLike
     with Inspectors
     with Matchers
     with ScalaFutures
     with MockitoSugar
     with DatastreamMetricsMock {

  trait Test {
    val logger = mock[Logger]
    val metrics = mockDatastreamMetrics()
    val httpResult: HttpResult

    val datastreamHandler = new DatastreamHandler(
      scheme = "http",
      host = "localhost",
      port = 1234,
      path = "/some/path",
      wsClient = mock[WSClient],
      logger = logger,
      metrics = metrics
    ) {
      override def sendHttpRequest(event: JsValue)(implicit ec: ExecutionContext): Future[HttpResult] =
        Future.successful(httpResult)
    }
  }

  "Any Datastream response" should {
    "Return Success for any response code of 2xx + increment counter" in forAll(200 to 299) { code =>
      new Test {
        val httpResult = HttpResult.Response(code)
        val result = datastreamHandler.sendEvent(JsString("SUCCESS")).futureValue
        result shouldBe HandlerResult.Success
        verify(metrics.successCounter, times(1)).inc()
        verifyNoInteractions(metrics.rejectCounter)
        verifyNoInteractions(metrics.failureCounter)
      }
    }

    "Return Failure + log error for any response code of 3XX or 401-412 or 414-499 or 5XX" in
      forAll((300 to 399) ++ (401 to 412) ++ (414 to 499) ++ (500 to 599)) { code =>
        new Test {
          val httpResult = HttpResult.Response(code)
          val result = datastreamHandler.sendEvent(JsString("FAILURE")).futureValue
          result shouldBe HandlerResult.Failure
          verify(logger).warn(s"AUDIT_FAILURE: received response with $code status code")

          verifyNoInteractions(metrics.successCounter)
          verifyNoInteractions(metrics.rejectCounter)
          verify(metrics.failureCounter, times(1)).inc()
        }
      }

    "Return Failure + log error for any malformed response" in new Test {
      val httpResult = HttpResult.Malformed

      val result = datastreamHandler.sendEvent(JsString("MALFORMED_FAILURE")).futureValue

      result shouldBe HandlerResult.Failure
      verify(logger).warn(s"AUDIT_FAILURE: received malformed response")
      verifyNoInteractions(metrics.successCounter)
      verifyNoInteractions(metrics.rejectCounter)
      verify(metrics.failureCounter, times(1)).inc()
    }


    "Return Failure + log error for any failure response (if error is available)" in new Test {
      val error = new Throwable("my error")
      val httpResult = HttpResult.Failure("my error message", Some(error))

      val result = datastreamHandler.sendEvent(JsString("FAILURE")).futureValue

      result shouldBe HandlerResult.Failure
      verify(logger).warn(s"AUDIT_FAILURE: failed with error 'my error message'", error)
      verifyNoInteractions(metrics.successCounter)
      verifyNoInteractions(metrics.rejectCounter)
      verify(metrics.failureCounter, times(1)).inc()
    }

    "Return Failure + log error for any failure response (if error is unavailable)" in new Test {
      val httpResult = HttpResult.Failure("my error message")
      val result = datastreamHandler.sendEvent(JsString("FAILURE")).futureValue

      result shouldBe HandlerResult.Failure
      verify(logger).warn(s"AUDIT_FAILURE: failed with error 'my error message'")
      verifyNoInteractions(metrics.successCounter)
      verifyNoInteractions(metrics.rejectCounter)
      verify(metrics.failureCounter, times(1)).inc()
    }

    "Return Rejected for any response code of 400 or 413" in forAll(Seq(400, 413)) { code =>
      new Test {
        val httpResult = HttpResult.Response(code)
        val result = datastreamHandler.sendEvent(JsString("REJECTED")).futureValue
        result shouldBe HandlerResult.Rejected
        verify(logger).warn(s"AUDIT_REJECTED: received response with $code status code")
        verifyNoInteractions(metrics.successCounter)
        verify(metrics.rejectCounter, times(1)).inc()
        verifyNoInteractions(metrics.failureCounter)
      }
    }
  }
}