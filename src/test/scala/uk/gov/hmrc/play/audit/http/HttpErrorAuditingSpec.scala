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

package uk.gov.hmrc.play.audit.http

import javax.inject.{Inject, Provider}

import org.mockito.{ArgumentCaptor, Mockito}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, MustMatchers, WordSpec}
import play.api.{Application, Configuration, Environment, OptionalSourceMapper}
import play.api.http.{DefaultHttpErrorHandler, HeaderNames, HttpErrorHandler, Writeable}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.routing.Router
import uk.gov.hmrc.play.audit.http.config.HttpErrorAuditing
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import play.api.test.Helpers._
import play.api.test.{FakeRequest, WithApplication}
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatestplus.play.{OneAppPerSuite, OneAppPerTest}
import uk.gov.hmrc.play.audit.{EventKeys, EventTypes}
import uk.gov.hmrc.play.audit.model.{AuditEvent, DataEvent}
import uk.gov.hmrc.play.http.{JsValidationException, NotFoundException}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class TestHttpErrorHandler @Inject() (
                                     environment: Environment,
                                     configuration: Configuration,
                                     sourceMapper: OptionalSourceMapper,
                                     router: Provider[Router],
                                     val auditConnector: AuditConnector
                                     ) extends DefaultHttpErrorHandler(environment, configuration, sourceMapper, router)
                                        with HttpErrorAuditing
                                        {

  override val appName: String = "Test"
  override implicit val ec: ExecutionContext = ExecutionContext.Implicits.global
}

class HttpErrorAuditingSpec extends WordSpec with MustMatchers with MockitoSugar with BeforeAndAfterEach {

  // Workaround because in tests Play allows exceptions to bubble up
  def routeWithError[A](app: Application, request: Request[A])
                       (implicit writeable: Writeable[A], ec: ExecutionContext): Option[Future[Result]] = {
    route(app, request)
      .map {
        _.recoverWith {
          case e =>
            app.errorHandler.onServerError(request, e)
        }
      }
  }

  val auditConnector: AuditConnector = mock[AuditConnector]

  val router: Router = {

    import play.api.mvc._
    import play.api.routing._
    import play.api.routing.sird._

    Router.from {

      case GET(p"/fail") => Action {
        _ => throw new Exception("Internal Server Error")
      }

      case GET(p"/fail-not-found") => Action {
        _ => throw new NotFoundException("Internal Server Error")
      }

      case GET(p"/fail-js-validation") => Action {
        _ => throw new JsValidationException(
          "", "", classOf[Nothing], Seq.empty
        )
      }

      case GET(p"/bad-request") => Action(BodyParsers.parse.json) {
        _ => Results.Ok
      }
    }
  }

  def app: Application = {

    import play.api.inject._

    new GuiceApplicationBuilder()
      .router(router)
      .overrides(
        bind[AuditConnector].toInstance(auditConnector),
        bind[HttpErrorHandler].to[TestHttpErrorHandler]
      )
      .build()
  }

  override def beforeEach(): Unit = {
    Mockito.reset(auditConnector)
    super.beforeEach()
  }

  "HttpErrorAuditing" must {

    "Audit a `ResourceNotFound` event on 404" in new WithApplication(app) {

      val captor = ArgumentCaptor.forClass(classOf[AuditEvent])
      val Some(result) = route(app, FakeRequest(GET, "/test"))

      status(result) mustEqual NOT_FOUND
      verify(auditConnector).sendEvent(captor.capture())(any(), any())

      val event = captor.getValue

      event.auditSource mustEqual "Test"
      event.auditType mustEqual "ResourceNotFound"
      event.tags must contain(EventKeys.TransactionName -> "Resource Endpoint Not Found")
    }

    "Audit a `ServerValidationError` event on 400" in new WithApplication(app) {

      val captor = ArgumentCaptor.forClass(classOf[AuditEvent])
      val Some(result) = route(app, FakeRequest(GET, "/bad-request")
        .withHeaders(HeaderNames.CONTENT_TYPE -> "application/json"))

      status(result) mustEqual BAD_REQUEST
      verify(auditConnector).sendEvent(captor.capture())(any(), any())

      val event = captor.getValue

      event.auditSource mustEqual "Test"
      event.auditType mustEqual "ServerValidationError"
      event.tags must contain(EventKeys.TransactionName -> "Request bad format exception")
    }

    "Audit a `ServerInternalError` event on 500" in new WithApplication(app) {

      val captor = ArgumentCaptor.forClass(classOf[AuditEvent])
      val Some(result) = routeWithError(app, FakeRequest(GET, "/fail"))

      status(result) mustEqual INTERNAL_SERVER_ERROR
      verify(auditConnector).sendEvent(captor.capture())(any(), any())

      val event = captor.getValue

      event.auditSource mustEqual "Test"
      event.auditType mustEqual "ServerInternalError"
      event.tags must contain(EventKeys.TransactionName -> "Unexpected error")

      val DataEvent(_, _, _, _, detail, _) = event

      detail must contain(EventTypes.TransactionFailureReason -> "Internal Server Error")
    }

    "Audit a `ResourceNotFound` event on 500 from upstream 404" in new WithApplication(app) {

      val captor = ArgumentCaptor.forClass(classOf[AuditEvent])
      val Some(result) = routeWithError(app, FakeRequest(GET, "/fail-not-found"))

      status(result) mustEqual INTERNAL_SERVER_ERROR
      verify(auditConnector).sendEvent(captor.capture())(any(), any())

      val event = captor.getValue

      event.auditSource mustEqual "Test"
      event.auditType mustEqual "ResourceNotFound"
      event.tags must contain(EventKeys.TransactionName -> "Unexpected error")

      val DataEvent(_, _, _, _, detail, _) = event

      detail must contain(EventTypes.TransactionFailureReason -> "Internal Server Error")
    }

    "Audit a `ServerValidationError` event on 500 from upstream validation exception" in new WithApplication(app) {

      val captor = ArgumentCaptor.forClass(classOf[AuditEvent])
      val Some(result) = routeWithError(app, FakeRequest(GET, "/fail-js-validation"))

      status(result) mustEqual INTERNAL_SERVER_ERROR
      verify(auditConnector).sendEvent(captor.capture())(any(), any())

      val event = captor.getValue

      event.auditSource mustEqual "Test"
      event.auditType mustEqual "ServerValidationError"
      event.tags must contain(EventKeys.TransactionName -> "Unexpected error")

      val DataEvent(_, _, _, _, detail, _) = event

      detail must contain key EventTypes.TransactionFailureReason
    }
  }
}
