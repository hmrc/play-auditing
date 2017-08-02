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

import org.joda.time.DateTime
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Inspectors, Matchers, WordSpecLike}
import play.api.Play
import play.api.test.FakeApplication
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.HeaderNames._
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.EventTypes._
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, MockAuditConnector}
import uk.gov.hmrc.play.audit.model.MergedDataEvent
import uk.gov.hmrc.play.test.Concurrent.await
import uk.gov.hmrc.play.test.Concurrent.liftFuture
import uk.gov.hmrc.play.test.DummyHttpResponse

import scala.concurrent.{ExecutionContext, Future}


class HttpAuditingSpec extends WordSpecLike with Matchers with Inspectors with Eventually with BeforeAndAfterAll {

  implicit def mockDatastreamConnector(ds: AuditConnector) : MockAuditConnector = ds.asInstanceOf[MockAuditConnector]

  val requestDateTime = new DateTime()
  val responseDateTime = requestDateTime.plusSeconds(5)

  lazy val fakeApplication = FakeApplication()

  override def beforeAll() {
    super.beforeAll()
    Play.start(fakeApplication)
  }

  override def afterAll() {
    super.afterAll()
    Play.stop(fakeApplication)
  }

  class HttpWithAuditing extends HttpAuditing {

    override lazy val appName: String = "httpWithAuditSpec"
    override lazy val auditConnector: AuditConnector = new MockAuditConnector
    implicit val ctx = ExecutionContext.Implicits.global

    def auditRequestWithResponseF(url: String, verb: String, requestBody: Option[_], response: Future[HttpResponse])(implicit hc: HeaderCarrier): Unit =
      AuditingHook(url, verb, requestBody, response)(hc, ctx)

    var now_call_count = 0
    override def now = {
      now_call_count=now_call_count+1

      if(now_call_count == 1) requestDateTime
      else responseDateTime
    }

    def buildRequest(url: String, verb: String, body: Option[_]) = {
      now_call_count = 1
      HttpRequest(url, verb, body, requestDateTime)
    }
  }

  sealed class HttpAuditingWithAuditException extends HttpWithAuditing {

    override lazy val auditConnector: AuditConnector = new MockAuditConnector {
      override def sendMergedEvent(event: MergedDataEvent)(implicit hc: HeaderCarrier, ec : ExecutionContext) = {
        throw new IllegalArgumentException("any exception")
      }
    }

  }

  "When asked to auditRequestWithResponseF the code" should {

      val aDeviceID = "A_DEVICE_ID"
      val serviceUri = "/service/path"

    "handle the happy path with a valid audit event passing through" in {

      val httpWithAudit = new HttpWithAuditing

      val requestBody = None
      val getVerb = "GET"
      val responseBody = "the response body"
      val statusCode = 200
      val response = Future.successful(new DummyHttpResponse(responseBody, statusCode))

      implicit val hcWithHeaders = HeaderCarrier(deviceID = Some(aDeviceID)).withExtraHeaders("Surrogate" -> "true")
      await(httpWithAudit.auditRequestWithResponseF(serviceUri, getVerb, requestBody, response))

      eventually(timeout(Span(1, Seconds))) {
        httpWithAudit.auditConnector.recordedMergedEvent shouldBe defined

        val dataEvent = httpWithAudit.auditConnector.recordedMergedEvent.get

        dataEvent.auditSource shouldBe httpWithAudit.appName
        dataEvent.auditType shouldBe OutboundCall

        dataEvent.request.tags shouldBe Map(xSessionId -> "-", xRequestId -> "-", TransactionName -> serviceUri, Path -> serviceUri, "clientIP" -> "-", "clientPort" -> "-", "Akamai-Reputation" -> "-")
        dataEvent.request.detail shouldBe Map("ipAddress" -> "-", authorisation -> "-", token -> "-", Path -> serviceUri, Method -> getVerb, "surrogate" -> "true", deviceID -> aDeviceID)
        dataEvent.request.generatedAt shouldBe requestDateTime

        dataEvent.response.tags shouldBe empty
        dataEvent.response.detail shouldBe Map(ResponseMessage -> responseBody, StatusCode -> statusCode.toString)
        dataEvent.response.generatedAt shouldBe responseDateTime
      }
    }

    "handle the case of an exception being raised inside the future and still send an audit message" in {

      implicit val hc = HeaderCarrier(deviceID = Some(aDeviceID))

      val httpWithAudit = new HttpWithAuditing

      val requestBody = "the infamous request body"
      val postVerb = "POST"
      val errorMessage = "FOO bar"
      val response = Future.failed(new Exception(errorMessage))

      await(httpWithAudit.auditRequestWithResponseF(serviceUri, postVerb, Some(requestBody), response))

      eventually(timeout(Span(1, Seconds))) {
        httpWithAudit.auditConnector.recordedMergedEvent shouldBe defined

        val dataEvent = httpWithAudit.auditConnector.recordedMergedEvent.get

        dataEvent.auditSource shouldBe httpWithAudit.appName
        dataEvent.auditType shouldBe OutboundCall

        dataEvent.request.tags shouldBe Map(xSessionId -> "-", xRequestId -> "-", TransactionName -> serviceUri, Path -> serviceUri, "clientIP" -> "-", "clientPort" -> "-", "Akamai-Reputation" -> "-")
        dataEvent.request.detail shouldBe Map("ipAddress" -> "-", authorisation -> "-", token -> "-", Path -> serviceUri, Method -> postVerb, RequestBody -> requestBody, deviceID -> aDeviceID)
        dataEvent.request.generatedAt shouldBe requestDateTime

        dataEvent.response.tags shouldBe empty
        dataEvent.response.detail should contain(FailedRequestMessage -> errorMessage)
        dataEvent.response.generatedAt shouldBe responseDateTime
      }
    }

    "not do anything if the datastream service is throwing an error as in this specific case datastream is logging the event" in {

      implicit val hc = HeaderCarrier()

      val httpWithAudit = new HttpAuditingWithAuditException

      val requestBody = "the infamous request body"
      val postVerb = "POST"
      val errorMessage = "FOO bar"
      val response = Future.failed(new Exception(errorMessage))

      await(httpWithAudit.auditRequestWithResponseF(serviceUri, postVerb, Some(requestBody), response))

      eventually(timeout(Span(1, Seconds))) {
        httpWithAudit.auditConnector.recordedMergedEvent shouldBe None
      }
    }

  }

  "Calling audit" should {
    val serviceUri = "/service/path"
    val aDeviceID = "A_DEVICE_ID"

    implicit val hc = HeaderCarrier(deviceID = Some(aDeviceID))

    "send unique event of type OutboundCall" in {
      val httpWithAudit = new HttpWithAuditing

      val requestBody = None
      val getVerb = "GET"
      val request = httpWithAudit.buildRequest(serviceUri, getVerb, requestBody)
      val response = new DummyHttpResponse("the response body", 200)

      implicit val hc = HeaderCarrier(deviceID = Some(aDeviceID), trueClientIp = Some("192.168.1.2"), trueClientPort = Some("12000")).withExtraHeaders("Surrogate" -> "true")

      httpWithAudit.audit(request, response)

      httpWithAudit.auditConnector.recordedMergedEvent shouldBe defined

      val dataEvent = httpWithAudit.auditConnector.recordedMergedEvent.get

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe OutboundCall

      dataEvent.request.tags shouldBe Map(xSessionId -> "-", xRequestId -> "-", TransactionName -> serviceUri, Path -> serviceUri, "clientIP" -> "192.168.1.2", "clientPort" -> "12000", "Akamai-Reputation" -> "-")
      dataEvent.request.detail shouldBe Map("ipAddress" -> "-", authorisation -> "-", token -> "-", Path -> serviceUri, Method -> getVerb, "surrogate" -> "true", deviceID -> aDeviceID)
      dataEvent.request.generatedAt shouldBe requestDateTime

      dataEvent.response.tags shouldBe empty
      dataEvent.response.detail shouldBe Map(ResponseMessage -> response.body, StatusCode -> response.status.toString)
      dataEvent.response.generatedAt shouldBe responseDateTime

    }

    "send unique event of type OutboundCall including the requestbody" in {
      val httpWithAudit = new HttpWithAuditing

      val postVerb = "POST"
      val requestBody = Some("The request body gets added to the audit details")
      val response = new DummyHttpResponse("the response body", 200)

      val request = httpWithAudit.buildRequest(serviceUri, postVerb, requestBody)
      httpWithAudit.audit(request, response)

      httpWithAudit.auditConnector.recordedMergedEvent shouldBe defined

      val dataEvent = httpWithAudit.auditConnector.recordedMergedEvent.get

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe OutboundCall

      dataEvent.request.tags shouldBe Map(xSessionId -> "-", xRequestId -> "-", TransactionName -> serviceUri, Path -> serviceUri, "clientIP" -> "-", "clientPort" -> "-", "Akamai-Reputation" -> "-")
      dataEvent.request.detail shouldBe Map("ipAddress" -> "-", authorisation -> "-", token -> "-", Path -> serviceUri, Method -> postVerb, RequestBody -> requestBody.get, deviceID -> aDeviceID)
      dataEvent.request.generatedAt shouldBe requestDateTime

      dataEvent.response.tags shouldBe empty
      dataEvent.response.detail shouldBe Map(ResponseMessage -> response.body, StatusCode -> response.status.toString)
      dataEvent.response.generatedAt shouldBe responseDateTime

    }
  }

  "Calling an internal microservice" should {
    val auditUris = Seq("service", "public.mdtp", "protected.mdtp", "private.mdtp", "monolith.mdtp", "foobar.mdtp", "mdtp").map { zone =>
      s"http://auth.$zone:80/auth/authority"
    }
    val getVerb = "GET"

    implicit val hc = HeaderCarrier()

    "not generate an audit event" in {
      forAll(auditUris) { auditUri =>
        val httpWithAudit = new HttpWithAuditing
        val requestBody = None
        val response = new DummyHttpResponse("the response body", 200)
        val request = httpWithAudit.buildRequest(auditUri, getVerb, requestBody)

        httpWithAudit.audit(request, response)

        httpWithAudit.auditConnector.recordedMergedEvent shouldBe None
      }
    }

    "not generate an audit event when an exception has been thrown" in {
      forAll(auditUris) { auditUri =>
        val httpWithAudit = new HttpWithAuditing
        val requestBody = None

        val request = httpWithAudit.buildRequest(auditUri, getVerb, requestBody)
        httpWithAudit.auditRequestWithException(request, "An exception occured when calling sendevent datastream")

        httpWithAudit.auditConnector.recordedMergedEvent shouldBe None
      }
    }
  }

  "Calling an external service with service in its domain name" should {
    val AuditUri = "http://some.service.gov.uk:80/self-assessment/data"
    val getVerb = "GET"

    implicit val hc = HeaderCarrier()

    "generate an audit event" in {
      val httpWithAudit = new HttpWithAuditing
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)

      httpWithAudit.audit(request, response)

      httpWithAudit.auditConnector.recordedMergedEvent shouldBe defined

      val dataEvent = httpWithAudit.auditConnector.recordedMergedEvent.get

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe OutboundCall
    }
  }

  "Auditing the url /write/audit" should {
    val AuditUri = "/write/audit"
    val getVerb = "GET"

    implicit val hc = HeaderCarrier()

    "not generate an audit event" in {
      val httpWithAudit = new HttpWithAuditing
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)

      httpWithAudit.audit(request, response)

      httpWithAudit.auditConnector.recordedMergedEvent shouldBe None
    }

    "not generate an audit event when an exception has been thrown" in {
      val httpWithAudit = new HttpWithAuditing
      val requestBody = None

      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)
      httpWithAudit.auditRequestWithException(request, "An exception occured when calling sendevent datastream")

      httpWithAudit.auditConnector.recordedMergedEvent shouldBe None

    }
  }

  "Auditing the url /write/audit/merged" should {
    val AuditUri = "/write/audit/merged"
    val getVerb = "GET"

    implicit val hc = HeaderCarrier()

    "not generate an audit event" in {
      val httpWithAudit = new HttpWithAuditing
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)



      httpWithAudit.audit(request, response)

      httpWithAudit.auditConnector.recordedMergedEvent shouldBe None
    }

    "not generate an audit event when an exception has been thrown" in  {
      val httpWithAudit = new HttpWithAuditing
      val requestBody = None

      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)
      httpWithAudit.auditRequestWithException(request, "An exception occured when calling sendevent datastream")

      httpWithAudit.auditConnector.recordedMergedEvent shouldBe None
    }

  }
}
