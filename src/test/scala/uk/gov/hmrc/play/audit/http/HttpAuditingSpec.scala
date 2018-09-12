/*
 * Copyright 2018 HM Revenue & Customs
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
import org.mockito.ArgumentCaptor
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{Inspectors, Matchers, WordSpecLike}
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.http.connector.AuditResult.Success
import uk.gov.hmrc.play.audit.model.MergedDataEvent
import uk.gov.hmrc.http.HeaderNames._
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, HttpResponse}
import uk.gov.hmrc.play.test.DummyHttpResponse

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class HttpAuditingSpec extends WordSpecLike with Matchers with Inspectors with Eventually with MockitoSugar {

  val outboundCallAuditType: String = "OutboundCall"
  val requestDateTime = new DateTime()
  val responseDateTime: DateTime = requestDateTime.plusSeconds(5)

  class HttpWithAuditing(connector: AuditConnector) extends HttpAuditing {
    override val appName: String = "httpWithAuditSpec"
    override def auditConnector: AuditConnector = connector

    def auditRequestWithResponseF(url: String, verb: String, requestBody: Option[_], response: Future[HttpResponse])(implicit hc: HeaderCarrier): Unit =
      AuditingHook(url, verb, requestBody, response)(hc, global)

    var now_call_count = 0
    override def now: DateTime = {
      now_call_count=now_call_count+1

      if(now_call_count == 1) requestDateTime
      else responseDateTime
    }

    def buildRequest(url: String, verb: String, body: Option[_]): HttpRequest = {
      now_call_count = 1
      HttpRequest(url, verb, body, requestDateTime)
    }
  }

  "When asked to auditRequestWithResponseF the code" should {

      val deviceID = "A_DEVICE_ID"
      val serviceUri = "/service/path"

    "handle the happy path with a valid audit event passing through" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = None
      val getVerb = "GET"
      val responseBody = "the response body"
      val statusCode = 200
      val response = Future.successful(new DummyHttpResponse(responseBody, statusCode))

      whenAuditSuccess(connector)

      implicit val hcWithHeaders = HeaderCarrier(deviceID = Some(deviceID)).withExtraHeaders("Surrogate" -> "true")
      httpWithAudit.auditRequestWithResponseF(serviceUri, getVerb, requestBody, response)

      eventually(timeout(Span(1, Seconds))) {
        val dataEvent = verifyAndRetrieveEvent(connector)

        dataEvent.auditSource shouldBe httpWithAudit.appName
        dataEvent.auditType shouldBe outboundCallAuditType

        dataEvent.request.tags shouldBe Map(xSessionId -> "-", xRequestId -> "-", Path -> serviceUri, "clientIP" -> "-", "clientPort" -> "-", "Akamai-Reputation" -> "-", HeaderNames.deviceID -> deviceID)
        dataEvent.request.detail shouldBe Map("ipAddress" -> "-", authorisation -> "-", token -> "-", Path -> serviceUri, Method -> getVerb, "surrogate" -> "true")
        dataEvent.request.generatedAt shouldBe requestDateTime

        dataEvent.response.tags shouldBe empty
        dataEvent.response.detail shouldBe Map(ResponseMessage -> responseBody, StatusCode -> statusCode.toString)
        dataEvent.response.generatedAt shouldBe responseDateTime
      }
    }

    "handle the case of an exception being raised inside the future and still send an audit message" in {
      implicit val hc = HeaderCarrier(deviceID = Some(deviceID))
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = "the infamous request body"
      val postVerb = "POST"
      val errorMessage = "FOO bar"
      val response = Future.failed(new Exception(errorMessage))

      whenAuditSuccess(connector)

      httpWithAudit.auditRequestWithResponseF(serviceUri, postVerb, Some(requestBody), response)

      eventually(timeout(Span(1, Seconds))) {
        val dataEvent = verifyAndRetrieveEvent(connector)

        dataEvent.auditSource shouldBe httpWithAudit.appName
        dataEvent.auditType shouldBe outboundCallAuditType

        dataEvent.request.tags shouldBe Map(xSessionId -> "-", xRequestId -> "-", Path -> serviceUri, "clientIP" -> "-", "clientPort" -> "-", "Akamai-Reputation" -> "-", HeaderNames.deviceID -> deviceID)
        dataEvent.request.detail shouldBe Map("ipAddress" -> "-", authorisation -> "-", token -> "-", Path -> serviceUri, Method -> postVerb, RequestBody -> requestBody)
        dataEvent.request.generatedAt shouldBe requestDateTime

        dataEvent.response.tags shouldBe empty
        dataEvent.response.detail should contain(FailedRequestMessage -> errorMessage)
        dataEvent.response.generatedAt shouldBe responseDateTime
      }
    }

    "not do anything if the datastream service is throwing an error as in this specific case datastream is logging the event" in {
      implicit val hc = HeaderCarrier()
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = "the infamous request body"
      val postVerb = "POST"
      val errorMessage = "FOO bar"
      val response = Future.failed(new Exception(errorMessage))

      when(connector.sendMergedEvent(any[MergedDataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenThrow(new IllegalArgumentException("any exception"))

      httpWithAudit.auditRequestWithResponseF(serviceUri, postVerb, Some(requestBody), response)

      eventually(timeout(Span(1, Seconds))) {
        verify(connector, times(1)).sendMergedEvent(any[MergedDataEvent])(any[HeaderCarrier], any[ExecutionContext])
        verifyNoMoreInteractions(connector)
      }
    }
  }

  "Calling audit" should {
    val serviceUri = "/service/path"
    val deviceID = "A_DEVICE_ID"

    implicit val hc = HeaderCarrier(deviceID = Some(deviceID))

    "send unique event of type OutboundCall" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = None
      val getVerb = "GET"
      val request = httpWithAudit.buildRequest(serviceUri, getVerb, requestBody)
      val response = new DummyHttpResponse("the response body", 200)

      implicit val hc = HeaderCarrier(deviceID = Some(deviceID), trueClientIp = Some("192.168.1.2"), trueClientPort = Some("12000")).withExtraHeaders("Surrogate" -> "true")

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe outboundCallAuditType

      dataEvent.request.tags shouldBe Map(xSessionId -> "-", xRequestId -> "-", Path -> serviceUri, "clientIP" -> "192.168.1.2", "clientPort" -> "12000", "Akamai-Reputation" -> "-", HeaderNames.deviceID -> deviceID)
      dataEvent.request.detail shouldBe Map("ipAddress" -> "-", authorisation -> "-", token -> "-", Path -> serviceUri, Method -> getVerb, "surrogate" -> "true")
      dataEvent.request.generatedAt shouldBe requestDateTime

      dataEvent.response.tags shouldBe empty
      dataEvent.response.detail shouldBe Map(ResponseMessage -> response.body, StatusCode -> response.status.toString)
      dataEvent.response.generatedAt shouldBe responseDateTime
    }

    "send unique event of type OutboundCall including the requestbody" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val postVerb = "POST"
      val requestBody = Some("The request body gets added to the audit details")
      val response = new DummyHttpResponse("the response body", 200)

      val request = httpWithAudit.buildRequest(serviceUri, postVerb, requestBody)

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe outboundCallAuditType

      dataEvent.request.tags shouldBe Map(xSessionId -> "-", xRequestId -> "-", Path -> serviceUri, "clientIP" -> "-", "clientPort" -> "-", "Akamai-Reputation" -> "-", HeaderNames.deviceID -> deviceID)
      dataEvent.request.detail shouldBe Map("ipAddress" -> "-", authorisation -> "-", token -> "-", Path -> serviceUri, Method -> postVerb, RequestBody -> requestBody.get)
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
        val connector = mock[AuditConnector]
        val httpWithAudit = new HttpWithAuditing(connector)
        val requestBody = None
        val response = new DummyHttpResponse("the response body", 200)
        val request = httpWithAudit.buildRequest(auditUri, getVerb, requestBody)

        httpWithAudit.audit(request, response)

        verifyZeroInteractions(connector)
      }
    }

    "not generate an audit event when an exception has been thrown" in {
      forAll(auditUris) { auditUri =>
        val connector = mock[AuditConnector]
        val httpWithAudit = new HttpWithAuditing(connector)
        val requestBody = None

        val request = httpWithAudit.buildRequest(auditUri, getVerb, requestBody)
        httpWithAudit.auditRequestWithException(request, "An exception occurred when calling sendevent datastream")

        verifyZeroInteractions(connector)
      }
    }
  }

  "Calling an external service with service in its domain name" should {
    val AuditUri = "http://some.service.gov.uk:80/self-assessment/data"
    val getVerb = "GET"

    implicit val hc = HeaderCarrier()

    "generate an audit event" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe outboundCallAuditType
    }
  }

  "Auditing the url /write/audit" should {
    val AuditUri = "/write/audit"
    val getVerb = "GET"

    implicit val hc = HeaderCarrier()

    "not generate an audit event" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)

      httpWithAudit.audit(request, response)

      verifyZeroInteractions(connector)
    }

    "not generate an audit event when an exception has been thrown" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None

      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)
      httpWithAudit.auditRequestWithException(request, "An exception occurred when calling sendevent datastream")

      verifyZeroInteractions(connector)
    }
  }

  "Auditing the url /write/audit/merged" should {
    val AuditUri = "/write/audit/merged"
    val getVerb = "GET"

    implicit val hc = HeaderCarrier()

    "not generate an audit event" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)

      httpWithAudit.audit(request, response)

      verifyZeroInteractions(connector)
    }

    "not generate an audit event when an exception has been thrown" in  {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None

      val request = httpWithAudit.buildRequest(AuditUri, getVerb, requestBody)
      httpWithAudit.auditRequestWithException(request, "An exception occured when calling sendevent datastream")

      verifyZeroInteractions(connector)
    }
  }

  def whenAuditSuccess(connector: AuditConnector): Unit = {
    when(connector.sendMergedEvent(any[MergedDataEvent])).thenReturn(Future.successful(Success))
  }

  def verifyAndRetrieveEvent(connector: AuditConnector): MergedDataEvent = {
    val captor = ArgumentCaptor.forClass(classOf[MergedDataEvent])
    verify(connector).sendMergedEvent(captor.capture)(any[HeaderCarrier], any[ExecutionContext])
    captor.getValue
  }
}
