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

package uk.gov.hmrc.play.audit.http

import java.time.Instant

import org.mockito.ArgumentCaptor
import org.scalatest.matchers.should.Matchers
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.Inspectors
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.http.connector.AuditResult.Success
import uk.gov.hmrc.play.audit.model.MergedDataEvent
import uk.gov.hmrc.http.HeaderNames._
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, HttpResponse}
import uk.gov.hmrc.http.hooks.HookData
import uk.gov.hmrc.play.test.DummyHttpResponse
import uk.gov.hmrc.http._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class HttpAuditingSpec
  extends AnyWordSpecLike
  with Matchers
  with Inspectors
  with Eventually
  with MockitoSugar {

  private val outboundCallAuditType: String = "OutboundCall"
  private val requestDateTime: Instant      = Instant.now
  private val responseDateTime: Instant     = requestDateTime.plusSeconds(5)
  private val postVerb                      = "POST"
  private val getVerb                       = "GET"

  class HttpWithAuditing(connector: AuditConnector) extends HttpAuditing {
    override val appName: String = "httpWithAuditSpec"
    override def auditConnector: AuditConnector = connector

    def auditRequestWithResponseF(verb: String, url: String, headers: Seq[(String, String)], requestBody: Option[HookData], response: Future[HttpResponse])(implicit hc: HeaderCarrier): Unit =
      AuditingHook(verb, url"$url", headers, requestBody, response)(hc, global)

    var now_call_count = 0
    override def now(): Instant = {
      now_call_count = now_call_count + 1

      if (now_call_count == 1) requestDateTime
      else responseDateTime
    }

    def buildRequest(verb: String, url: String, headers: Seq[(String, String)], body: Option[HookData]): HttpRequest = {
      now_call_count = 1
      HttpRequest(verb, url, headers, body, requestDateTime)
    }
  }

  "When asked to auditRequestWithResponseF the code" should {
    val deviceID = "A_DEVICE_ID"
    val serviceUri = "https://www.google.co.uk"

    "handle the happy path with a valid audit event passing through" in {
      val connector = mock[AuditConnector]
      when(connector.auditSentHeaders).thenReturn(true)
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = None
      val responseBody = "the response body"
      val statusCode = 200
      val response = Future.successful(new DummyHttpResponse(responseBody, statusCode))

      whenAuditSuccess(connector)

      val hc: HeaderCarrier = HeaderCarrier(deviceID = Some(deviceID))

      val sentHeaders = Seq(
        "surrogate"          -> "true",
        "authorization"      -> "token",
        "allowlist-header"   -> "test-value"
      )

      httpWithAudit.auditRequestWithResponseF(getVerb, serviceUri, sentHeaders, requestBody, response)(hc)

      eventually(timeout(Span(1, Seconds))) {
        val dataEvent = verifyAndRetrieveEvent(connector)

        dataEvent.auditSource shouldBe httpWithAudit.appName
        dataEvent.auditType shouldBe outboundCallAuditType

        dataEvent.request.tags shouldBe Map(
          xSessionId          -> "-",
          xRequestId          -> "-",
          Path                -> serviceUri,
          "clientIP"          -> "-",
          "clientPort"        -> "-",
          "Akamai-Reputation" -> "-",
          HeaderNames.deviceID -> deviceID
        )
        dataEvent.request.detail shouldBe Map(
          "ipAddress"          -> "-",
          authorisation        -> "token",
          Path                 -> serviceUri,
          Method               -> getVerb,
          "surrogate"          -> "true",
          "allowlist-header"   -> "test-value"
        )
        dataEvent.request.generatedAt shouldBe requestDateTime

        dataEvent.response.tags shouldBe empty
        dataEvent.response.detail shouldBe Map(
          ResponseMessage -> responseBody,
          StatusCode      -> statusCode.toString
        )
        dataEvent.response.generatedAt shouldBe responseDateTime
      }
    }

    "not audit extra headers by default" in {
      val connector = mock[AuditConnector]
      when(connector.auditSentHeaders).thenReturn(false)
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = None
      val responseBody = "the response body"
      val statusCode = 200
      val response = Future.successful(new DummyHttpResponse(responseBody, statusCode))

      whenAuditSuccess(connector)

      val hc: HeaderCarrier = HeaderCarrier(deviceID = Some(deviceID))
      val sentHeaders       = Seq(
        surrogate      -> "true",
        "extra-header" -> "test-value"
      )

      httpWithAudit.auditRequestWithResponseF(getVerb, serviceUri, sentHeaders, requestBody, response)(hc)

      eventually(timeout(Span(1, Seconds))) {
        val dataEvent = verifyAndRetrieveEvent(connector)
        dataEvent.request.detail shouldNot contain key "extra-header"
       }
    }

    "handle the case of an exception being raised inside the future and still send an audit message" in {
      implicit val hc: HeaderCarrier = HeaderCarrier(deviceID = Some(deviceID))
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = "the infamous request body"
      val errorMessage = "FOO bar"
      val response = Future.failed(new Exception(errorMessage))

      whenAuditSuccess(connector)

      httpWithAudit.auditRequestWithResponseF(postVerb, serviceUri, Seq(), Some(HookData.FromString(requestBody)), response)

      eventually(timeout(Span(1, Seconds))) {
        val dataEvent = verifyAndRetrieveEvent(connector)

        dataEvent.auditSource shouldBe httpWithAudit.appName
        dataEvent.auditType shouldBe outboundCallAuditType

        dataEvent.request.tags shouldBe Map(
          xSessionId           -> "-",
          xRequestId           -> "-",
          Path                 -> serviceUri,
          "clientIP"           -> "-",
          "clientPort"         -> "-",
          "Akamai-Reputation"  -> "-",
          HeaderNames.deviceID -> deviceID
        )
        dataEvent.request.detail shouldBe Map(
          "ipAddress"   -> "-",
          authorisation -> "-",
          Path          -> serviceUri,
          Method        -> postVerb,
          RequestBody   -> requestBody
        )
        dataEvent.request.generatedAt shouldBe requestDateTime

        dataEvent.response.tags shouldBe empty
        dataEvent.response.detail should contain(FailedRequestMessage -> errorMessage)
        dataEvent.response.generatedAt shouldBe responseDateTime
      }
    }

    "not do anything if the datastream service is throwing an error as in this specific case datastream is logging the event" in {
      val hc: HeaderCarrier = HeaderCarrier()
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = "the infamous request body"
      val errorMessage = "FOO bar"
      val response = Future.failed(new Exception(errorMessage))

      when(connector.sendMergedEvent(any[MergedDataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenThrow(new IllegalArgumentException("any exception"))

      when(connector.auditSentHeaders).thenReturn(false)

      httpWithAudit.auditRequestWithResponseF(postVerb, serviceUri, Seq.empty, Some(HookData.FromString(requestBody)), response)(hc)

      eventually(timeout(Span(1, Seconds))) {
        verify(connector, times(1)).sendMergedEvent(any[MergedDataEvent])(any[HeaderCarrier], any[ExecutionContext])
        verify(connector).auditSentHeaders
        verifyNoMoreInteractions(connector)
      }
    }
  }

  "Calling audit" should {
    val serviceUri = "/service/path"
    val deviceID = "A_DEVICE_ID"

    implicit val hc: HeaderCarrier = HeaderCarrier(deviceID = Some(deviceID))

    "send unique event of type OutboundCall" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = None
      val request = httpWithAudit.buildRequest(getVerb, serviceUri, Seq(surrogate -> "true") , requestBody)
      val response = new DummyHttpResponse("the response body", 200)

      implicit val hc: HeaderCarrier = HeaderCarrier(
        deviceID       = Some(deviceID),
        trueClientIp   = Some("192.168.1.2"),
        trueClientPort = Some("12000")
      )

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe outboundCallAuditType

      dataEvent.request.tags shouldBe Map(
        xSessionId           -> "-",
        xRequestId           -> "-",
        Path                 -> serviceUri,
        "clientIP"           -> "192.168.1.2",
        "clientPort"         -> "12000",
        "Akamai-Reputation"  -> "-",
        HeaderNames.deviceID -> deviceID
      )
      dataEvent.request.detail shouldBe Map(
        "ipAddress"   -> "-",
        authorisation -> "-",
        Path          -> serviceUri,
        Method        -> getVerb,
        "surrogate"   -> "true"
      )
      dataEvent.request.generatedAt shouldBe requestDateTime

      dataEvent.response.tags shouldBe empty
      dataEvent.response.detail shouldBe Map(ResponseMessage -> response.body, StatusCode -> response.status.toString)
      dataEvent.response.generatedAt shouldBe responseDateTime
    }

    "send unique event of type OutboundCall including the requestbody" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody   = "The request body gets added to the audit details"
      val response      = new DummyHttpResponse("the response body", 200)

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(HookData.FromString(requestBody)))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe outboundCallAuditType

      dataEvent.request.tags shouldBe Map(
        xSessionId            -> "-",
        xRequestId            -> "-",
        Path                  -> serviceUri,
        "clientIP"            -> "-",
        "clientPort"          -> "-",
        "Akamai-Reputation"   -> "-",
         HeaderNames.deviceID -> deviceID
      )
      dataEvent.request.detail shouldBe Map(
        "ipAddress"   -> "-",
        authorisation -> "-",
        Path          -> serviceUri,
        Method        -> postVerb,
        RequestBody   -> requestBody
      )
      dataEvent.request.generatedAt shouldBe requestDateTime

      dataEvent.response.tags shouldBe empty
      dataEvent.response.detail shouldBe Map(ResponseMessage -> response.body, StatusCode -> response.status.toString)
      dataEvent.response.generatedAt shouldBe responseDateTime
    }

    "mask passwords in an OutboundCall using form values" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = Map(
        "ok"                   -> Seq("a"),
        "password"             -> Seq("hide-me"),
        "passwordConfirmation" -> Seq("hide-me")
      )
      val response = new DummyHttpResponse(Json.obj("password" -> "hide-me").toString, 200)

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(HookData.FromMap(requestBody)))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody) shouldBe requestBody.toString.replaceAllLiterally("List(hide-me)", "########")
      dataEvent.response.detail(ResponseMessage) shouldBe response.body.replaceAllLiterally("hide-me", "########")
    }

    "mask passwords in an OutboundCall using json" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val sampleJson = Json.obj("a" -> Json.arr(Json.obj("ok" -> 1, "password" -> "hide-me", "PASSWD" -> "hide-me")))
      val response = new DummyHttpResponse(Json.stringify(sampleJson), 200)

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(HookData.FromString(Json.stringify(sampleJson))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody) shouldBe sampleJson.toString.replaceAllLiterally("hide-me", "########")
      dataEvent.response.detail(ResponseMessage) shouldBe response.body.replaceAllLiterally("hide-me", "########")
    }

    "mask passwords in an OutboundCall using xml" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val sampleXml =
        """<abc>
          |    <foo>
          |        <Password>hide-me</Password>
          |        <prefix:password>hide-me</prefix:password>
          |    </foo>
          |    <bar PassWord="hide-me" prefix:PASSWORD="hide-me"/>
          |</abc>""".stripMargin
      val response = new DummyHttpResponse(sampleXml, 200)

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(HookData.FromString(sampleXml)))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody) shouldBe sampleXml.toString.replaceAllLiterally("hide-me", "########")
      dataEvent.response.detail(ResponseMessage) shouldBe response.body.replaceAllLiterally("hide-me", "########")
    }

    "handle an invalid xml request and response body" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = "< this is not xml"
      val response = new DummyHttpResponse("< this is not xml", 200)

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(HookData.FromString(requestBody)))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody) shouldBe requestBody
      dataEvent.response.detail(ResponseMessage) shouldBe response.body
    }

    "handle an invalid json request and response body" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = "{ not json"
      val response = new DummyHttpResponse("{ not json", 200)

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(HookData.FromString(requestBody)))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody) shouldBe requestBody
      dataEvent.response.detail(ResponseMessage) shouldBe response.body
    }
  }

  "Calling an internal microservice" should {
    val auditUris = Seq("service", "public.mdtp", "protected.mdtp", "private.mdtp", "monolith.mdtp", "foobar.mdtp", "mdtp").map { zone =>
      s"http://auth.$zone:80/auth/authority"
    }
    val getVerb = "GET"

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "not generate an audit event" in {
      forAll(auditUris) { auditUri =>
        val connector = mock[AuditConnector]
        val httpWithAudit = new HttpWithAuditing(connector)
        val requestBody = None
        val response = new DummyHttpResponse("the response body", 200)
        val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)

        httpWithAudit.audit(request, response)

        verifyNoInteractions(connector)
      }
    }

    "not generate an audit event when an exception has been thrown" in {
      forAll(auditUris) { auditUri =>
        val connector = mock[AuditConnector]
        val httpWithAudit = new HttpWithAuditing(connector)
        val requestBody = None

        val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)
        httpWithAudit.auditRequestWithException(request, "An exception occurred when calling sendevent datastream")

        verifyNoInteractions(connector)
      }
    }
  }

  "Calling an external service with service in its domain name" should {
    val auditUri = "http://some.service.gov.uk:80/self-assessment/data"

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "generate an audit event" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, response)

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe outboundCallAuditType
    }
  }

  "Auditing the url /write/audit" should {
    val auditUri = "/write/audit"

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "not generate an audit event" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)

      httpWithAudit.audit(request, response)

      verifyNoInteractions(connector)
    }

    "not generate an audit event when an exception has been thrown" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None

      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)
      httpWithAudit.auditRequestWithException(request, "An exception occurred when calling sendevent datastream")

      verifyNoInteractions(connector)
    }
  }

  "Auditing the url /write/audit/merged" should {
    val auditUri = "/write/audit/merged"

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "not generate an audit event" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None
      val response = new DummyHttpResponse("the response body", 200)
      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)

      httpWithAudit.audit(request, response)

      verifyNoInteractions(connector)
    }

    "not generate an audit event when an exception has been thrown" in  {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody = None

      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)
      httpWithAudit.auditRequestWithException(request, "An exception occured when calling sendevent datastream")

      verifyNoInteractions(connector)
    }
  }

  "caseInsensitiveMap" should {

    "comma separate values for duplicate keys" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val headers = Seq("a" -> "a", "a" -> "b", "a" -> "c", "b" -> "d")

      val result = httpWithAudit.caseInsensitiveMap(headers)

      result shouldBe Map("a" -> "a,b,c", "b" -> "d")
    }

    "treat keys in a case insensitive way when returning values" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val headers = Seq(
        "aa" -> "a",
        "Aa" -> "b",
        "Aa" -> "c",
        "AA" -> "d",
        "b" -> "d"
      )

      val result = httpWithAudit.caseInsensitiveMap(headers)

      result shouldBe Map("aa" -> "a,b,c,d", "b" -> "d")
      result.get("aA") shouldBe Some("a,b,c,d")
      result.get("Aa") shouldBe Some("a,b,c,d")
      result.get("AA") shouldBe Some("a,b,c,d")
    }

    "preserve the case of the first header name when there are duplicates" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val headers = Seq(
        "X-forwarded-for" -> "a",
        "X-forwarded-for" -> "b",
        "x-forwarded-for" -> "c",
        "X-FORWARDED-for" -> "d",
        "b" -> "d"
      )

      val result = httpWithAudit.caseInsensitiveMap(headers)

      result shouldBe Map("X-forwarded-for" -> "a,b,c,d", "b" -> "d")
    }
  }

  def whenAuditSuccess(connector: AuditConnector): Unit =
    when(connector.sendMergedEvent(any[MergedDataEvent]))
      .thenReturn(Future.successful(Success))

  def verifyAndRetrieveEvent(connector: AuditConnector): MergedDataEvent = {
    val captor = ArgumentCaptor.forClass(classOf[MergedDataEvent])
    verify(connector).sendMergedEvent(captor.capture)(any[HeaderCarrier], any[ExecutionContext])
    captor.getValue
  }
}
