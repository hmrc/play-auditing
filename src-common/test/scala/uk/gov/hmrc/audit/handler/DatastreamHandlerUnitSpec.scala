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

import org.scalatest.Inspectors
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{JsString, JsValue}
import uk.gov.hmrc.audit.{HandlerResult, WSClient}

import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global

class DatastreamHandlerUnitSpec
  extends AnyWordSpecLike
     with Inspectors
     with Matchers
     with ScalaFutures
     with MockitoSugar {

  val datastreamHandler = new DatastreamHandler(
    scheme   = "http",
    host     = "localhost",
    port     = 1234,
    path     = "/some/path",
    wsClient = mock[WSClient]
    ) {
    override def sendHttpRequest(event: JsValue)(implicit ec: ExecutionContext): Future[HttpResult] =
      Future.successful(HttpResult.Response(event.as[String].toInt))
  }

  "Any Datastream response" should {
    "Return Success for any response code of 204" in {
      val result = datastreamHandler.sendEvent(JsString("204")).futureValue
      result shouldBe HandlerResult.Success
    }

//    "Return Failure for any response code of 3XX or 401-412 or 414-499 or 5XX" in {
//      forAll((300 to 399) ++ (401 to 412) ++ (414 to 499) ++ (500 to 599)) { code =>
//        val result = datastreamHandler.sendEvent(JsString(code.toString)).futureValue
//        result shouldBe HandlerResult.Failure
//      }
//    }

    "Return Rejected for any response code of 400 or 413" in {
      forAll(Seq(400, 413)) { code =>
        val result = datastreamHandler.sendEvent(JsString(code.toString)).futureValue
        result shouldBe HandlerResult.Rejected
      }
    }
  }
}
