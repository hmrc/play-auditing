/*
 * Copyright 2015 HM Revenue & Customs
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

package uk.gov.hmrc.play.audit.filters

import play.api.Routes
import play.api.libs.iteratee._
import play.api.mvc.{Result, _}
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.EventTypes
import uk.gov.hmrc.play.audit.http.HttpAuditEvent
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, Auditor}
import uk.gov.hmrc.play.http.HeaderCarrier

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success, Try}

trait AuditFilter extends EssentialFilter with HttpAuditEvent {

  def auditConnector: AuditConnector

  def controllerNeedsAuditing(controllerName: String): Boolean

  protected def needsAuditing(request: RequestHeader): Boolean =
    (for (controllerName <- request.tags.get(Routes.ROUTE_CONTROLLER))
      yield controllerNeedsAuditing(controllerName)).getOrElse(true)

  protected def getBody(result: Result) = {
    val bytesToString: Enumeratee[Array[Byte], String] = Enumeratee.map[Array[Byte]] { bytes => new String(bytes) }
    val consume: Iteratee[String, String] = Iteratee.consume[String]()
    result.body |>>> bytesToString &>> consume
  }

  protected def captureRequestBody(next: Iteratee[Array[Byte], Result], onDone: Promise[Array[Byte]]): Iteratee[Array[Byte], Result] = {
    def step(body: Array[Byte], nextI: Iteratee[Array[Byte], Result])(input: Input[Array[Byte]]): Iteratee[Array[Byte], Result] = {
      input match {
        case Input.El(e) => Cont[Array[Byte], Result](step(Array.concat(body, e), Iteratee.flatten(nextI.feed(Input.El(e)))))
        case Input.Empty => Cont[Array[Byte], Result](step(body, nextI))
        case Input.EOF => {
          val result = Iteratee.flatten(nextI.feed(Input.EOF))
          onDone.success(body)
          result
        }
      }
    }

    Cont[Array[Byte], Result](i => step(Array(), next)(i))
  }

  protected def captureResult(next: Iteratee[Array[Byte], Result], requestBody: Future[Array[Byte]])(handler: (Array[Byte], Try[Result]) => Unit): Iteratee[Array[Byte], Result] = {
    next.map { result =>
      val collectedBody = new ArrayBuffer[Byte](0)

      def collect(i: Array[Byte]) = { collectedBody.appendAll(i); i }
      def handleSuccess() = requestBody.onSuccess { case body =>
        handler(body, Success(result.copy(body = Enumerator(collectedBody.toArray))))
      }

      result.copy(body = result.body.map { collect } onDoneEnumerating handleSuccess)

    }.recoverWith { case ex: Throwable =>
      requestBody.onSuccess { case body => handler(body, Failure(ex)) }
      next
    }
  }

  def apply(nextFilter: EssentialAction) = new EssentialAction {
    def apply(requestHeader: RequestHeader) = {
      val next = nextFilter(requestHeader)
      implicit val hc = HeaderCarrier.fromHeadersAndSession(requestHeader.headers)

      def performAudit(input: Array[Byte], maybeResult: Try[Result]): Unit = {
        maybeResult match {
          case Success(result) =>
            getBody(result) map { responseBody =>
              auditConnector.sendEvent(
                dataEvent(EventTypes.RequestReceived, requestHeader.uri, requestHeader)
                  .withDetail(ResponseMessage -> new String(responseBody), StatusCode -> result.header.status.toString))
            }
          case Failure(f) =>
            auditConnector.sendEvent(
              dataEvent(EventTypes.RequestReceived, requestHeader.uri, requestHeader)
                .withDetail(FailedRequestMessage -> f.getMessage))
        }
      }

      if (needsAuditing(requestHeader)) {
        val requestBodyPromise = Promise[Array[Byte]]()

        val result = captureResult(next, requestBodyPromise.future)(performAudit)
        captureRequestBody(result, requestBodyPromise)
      }
      else next
    }
  }
}