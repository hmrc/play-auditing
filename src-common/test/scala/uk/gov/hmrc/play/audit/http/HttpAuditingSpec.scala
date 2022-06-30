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
import org.mockito.captor.ArgCaptor
import org.scalatest.matchers.should.Matchers
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.Inspectors
import play.api.libs.json.Json
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.http.connector.AuditResult.Success
import uk.gov.hmrc.play.audit.model.{MergedDataEvent, RedactionLog}
import uk.gov.hmrc.http.HeaderNames._
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames}
import uk.gov.hmrc.http.hooks.{Data, HookData, RequestData, ResponseData}
import uk.gov.hmrc.http._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class HttpAuditingSpec
  extends AnyWordSpecLike
     with Matchers
     with Inspectors
     with Eventually
     with MockitoSugar
     with ArgumentMatchersSugar {

  private val outboundCallAuditType: String = "OutboundCall"
  private val requestDateTime: Instant      = Instant.now
  private val responseDateTime: Instant     = requestDateTime.plusSeconds(5)
  private val postVerb                      = "POST"
  private val getVerb                       = "GET"

  "When asked to auditRequestWithResponseF the code" should {
    val deviceID = "A_DEVICE_ID"
    val serviceUri = "https://www.google.co.uk"

    "handle the happy path with a valid audit event passing through" in {
      val connector = mock[AuditConnector]
      when(connector.isEnabled).thenReturn(true)
      when(connector.auditSentHeaders).thenReturn(true)
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody  = None
      val responseBody = "the response body"
      val statusCode   = 200
      val responseF    = Future.successful(ResponseData(
                           body    = Data.pure(responseBody),
                           status  = statusCode,
                           headers = Map.empty
                         ))

      whenAuditSuccess(connector)

      val hc: HeaderCarrier = HeaderCarrier(deviceID = Some(deviceID))

      val sentHeaders = Seq(
        "surrogate"        -> "true",
        "authorization"    -> "token",
        "allowlist-header" -> "test-value"
      )
      val request = RequestData(headers = sentHeaders, body = requestBody)

      httpWithAudit.auditRequestWithResponseF(getVerb, serviceUri, request, responseF)(hc)

      eventually(timeout(Span(1, Seconds))) {
        val dataEvent = verifyAndRetrieveEvent(connector)

        dataEvent.auditSource shouldBe httpWithAudit.appName
        dataEvent.auditType   shouldBe outboundCallAuditType

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

        dataEvent.response.tags   shouldBe empty
        dataEvent.response.detail shouldBe Map(
          ResponseMessage -> responseBody,
          StatusCode      -> statusCode.toString
        )
        dataEvent.response.generatedAt shouldBe responseDateTime
      }
    }

    "not audit extra headers by default" in {
      val connector = mock[AuditConnector]
      when(connector.isEnabled).thenReturn(true)
      when(connector.auditSentHeaders).thenReturn(false)
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody  = None
      val responseBody = "the response body"
      val statusCode   = 200
      val responseF    = Future.successful(ResponseData(
                           body    = Data.pure(responseBody),
                           status  = statusCode,
                           headers = Map.empty
                         ))

      whenAuditSuccess(connector)

      val hc = HeaderCarrier(deviceID = Some(deviceID))

      val sentHeaders = Seq(
        surrogate      -> "true",
        "extra-header" -> "test-value"
      )
      val request = RequestData(
        headers = sentHeaders,
        body    = requestBody
      )

      httpWithAudit.auditRequestWithResponseF(getVerb, serviceUri, request, responseF)(hc)

      eventually(timeout(Span(1, Seconds))) {
        val dataEvent = verifyAndRetrieveEvent(connector)
        dataEvent.request.detail shouldNot contain key "extra-header"
       }
    }

    "handle the case of an exception being raised inside the future and still send an audit message" in {
      val hc            = HeaderCarrier(deviceID = Some(deviceID))
      val connector     = mock[AuditConnector]
      when(connector.isEnabled).thenReturn(true)
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody  = "the infamous request body"
      val errorMessage = "FOO bar"
      val responseF    = Future.failed(new Exception(errorMessage))

      whenAuditSuccess(connector)

      val request = RequestData(
        headers = Seq.empty,
        body    = Some(Data.pure(HookData.FromString(requestBody)))
      )

      httpWithAudit.auditRequestWithResponseF(postVerb, serviceUri, request, responseF)(hc)

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
          "ipAddress"        -> "-",
          authorisation      -> "-",
          Path               -> serviceUri,
          Method             -> postVerb,
          RequestBody        -> requestBody
        )
        dataEvent.request.generatedAt shouldBe requestDateTime

        dataEvent.response.tags        shouldBe empty
        dataEvent.response.detail      should contain(FailedRequestMessage -> errorMessage)
        dataEvent.response.generatedAt shouldBe responseDateTime
      }
    }

    "not do anything if the datastream service is throwing an error as in this specific case datastream is logging the event" in {
      val hc            = HeaderCarrier()
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody  = "the infamous request body"
      val errorMessage = "FOO bar"
      val responseF    = Future.failed(new Exception(errorMessage))

      val request = RequestData(
        headers = Seq.empty,
        body    = Some(Data.pure(HookData.FromString(requestBody)))
      )

      when(connector.sendMergedEvent(any[MergedDataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenThrow(new IllegalArgumentException("any exception"))

      when(connector.isEnabled)
        .thenReturn(true)

      when(connector.auditSentHeaders)
        .thenReturn(false)

      httpWithAudit.auditRequestWithResponseF(postVerb, serviceUri, request, responseF)(hc)

      eventually(timeout(Span(1, Seconds))) {
        verify(connector, times(1)).sendMergedEvent(any[MergedDataEvent])(any[HeaderCarrier], any[ExecutionContext])
        verify(connector).isEnabled
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

      val requestBody  = None
      val request      = httpWithAudit.buildRequest(getVerb, serviceUri, Seq(surrogate -> "true"), requestBody)
      val responseBody = "the response body"
      val response     = ResponseData(
                           body    = Data.pure(responseBody),
                           status  = 200,
                           headers = Map.empty
                         )

      implicit val hc: HeaderCarrier = HeaderCarrier(
        deviceID       = Some(deviceID),
        trueClientIp   = Some("192.168.1.2"),
        trueClientPort = Some("12000")
      )

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType   shouldBe outboundCallAuditType

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

      dataEvent.response.tags   shouldBe empty
      dataEvent.response.detail shouldBe Map(
        ResponseMessage -> responseBody,
        StatusCode      -> response.status.toString
      )
      dataEvent.response.generatedAt shouldBe responseDateTime
    }

    "send unique event of type OutboundCall including the requestbody" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody   = "The request body gets added to the audit details"
      val responseBody  = "the response body"
      val response      = ResponseData(
                             body    = Data.pure(responseBody),
                             status  = 200,
                             headers = Map.empty
                           )

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(Data.pure(HookData.FromString(requestBody))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

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
        "ipAddress"        -> "-",
        authorisation      -> "-",
        Path               -> serviceUri,
        Method             -> postVerb,
        RequestBody        -> requestBody
      )
      dataEvent.request.generatedAt shouldBe requestDateTime

      dataEvent.response.tags shouldBe empty
      dataEvent.response.detail shouldBe Map(
        ResponseMessage     -> responseBody,
        StatusCode          -> response.status.toString
      )
      dataEvent.response.generatedAt shouldBe responseDateTime

      dataEvent.redactionLog shouldBe RedactionLog.Empty
    }

    "mask passwords in an OutboundCall using form values" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = Map(
        "ok"                   -> Seq("a"),
        "password"             -> Seq("hide-me"),
        "passwordConfirmation" -> Seq("hide-me")
      )

      val responseBody = Json.obj("password" -> "hide-me").toString
      val response = ResponseData(
        body    = Data.pure(responseBody),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(Data.pure(HookData.FromMap(requestBody))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody) shouldBe requestBody.toString.replace("List(hide-me)", "########")
      dataEvent.response.detail(ResponseMessage) shouldBe responseBody.replace("hide-me", "########")

      dataEvent.redactionLog.redactedFields shouldBe List("request.detail.requestBody", "response.detail.responseMessage")
    }

    "mask passwords in an OutboundCall using json" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val sampleJson = Json.obj("a" -> Json.arr(Json.obj("ok" -> 1, "password" -> "hide-me", "PASSWD" -> "hide-me"))).toString
      val requestBody = sampleJson
      val responseBody = sampleJson

      val response = ResponseData(
        body    = Data.pure(responseBody),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(Data.pure(HookData.FromString(requestBody))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      Json.parse(dataEvent.request.detail(RequestBody)) shouldBe Json.parse(requestBody.replace("hide-me", "########"))
      Json.parse(dataEvent.response.detail(ResponseMessage)) shouldBe Json.parse(responseBody.replace("hide-me", "########"))

      dataEvent.redactionLog.redactedFields shouldBe List("request.detail.requestBody", "response.detail.responseMessage")
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

      val requestBody = sampleXml
      val responseBody = sampleXml
      val response = ResponseData(
                           body    = Data.pure(responseBody),
                           status  = 200,
                           headers = Map.empty
                         )

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(Data.pure(HookData.FromString(requestBody))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody)      shouldBe requestBody.replace("hide-me", "########")
      dataEvent.response.detail(ResponseMessage) shouldBe responseBody.replace("hide-me", "########")

      dataEvent.redactionLog.redactedFields shouldBe List("request.detail.requestBody", "response.detail.responseMessage")
    }

    "handle an invalid xml request and response body" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody  = "< this is not xml"
      val responseBody = "< this is also not xml"

      val response = ResponseData(
        body    = Data.pure(responseBody),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(Data.pure(HookData.FromString(requestBody))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody)      shouldBe requestBody
      dataEvent.response.detail(ResponseMessage) shouldBe responseBody
    }

    "handle an invalid json request and response body" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody  = "{ not json"
      val responseBody = "{ also not json"

      val response = ResponseData(
        body    = Data.pure(responseBody),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(Data.pure(HookData.FromString(requestBody))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody)      shouldBe requestBody
      dataEvent.response.detail(ResponseMessage) shouldBe responseBody
    }

    "indicate if the request body was truncated" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = "truncated body"

      val responseBody = "complete body"
      val response = ResponseData(
        body    = Data.pure(responseBody),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(Data.truncated(HookData.FromString(requestBody))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody)      shouldBe requestBody
      dataEvent.truncationLog.truncatedFields    shouldBe List("request.detail.requestBody")
      dataEvent.response.detail(ResponseMessage) shouldBe responseBody
    }

    "indicate if the response body was truncated" in {
      val connector = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val requestBody = "complete body"

      val responseBody = "truncated body"
      val response = ResponseData(
        body    = Data.truncated(responseBody),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(postVerb, serviceUri, Seq.empty, Some(Data.pure(HookData.FromString(requestBody))))

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.request.detail(RequestBody)      shouldBe requestBody
      dataEvent.response.detail(ResponseMessage) shouldBe responseBody
      dataEvent.truncationLog.truncatedFields    shouldBe List("response.detail.responseMessage")
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
        val connector     = mock[AuditConnector]
        val httpWithAudit = new HttpWithAuditing(connector)
        val requestBody   = None

        val response = ResponseData(
          body    = Data.pure("the response body"),
          status  = 200,
          headers = Map.empty
        )

        val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)

        httpWithAudit.audit(request, Right(response))

        verifyNoMoreInteractions(connector)
      }
    }

    "not generate an audit event when an exception has been thrown" in {
      forAll(auditUris) { auditUri =>
        val connector     = mock[AuditConnector]
        val httpWithAudit = new HttpWithAuditing(connector)
        val requestBody   = None

        val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)
        httpWithAudit.audit(request, Left("An exception occurred when calling sendevent datastream"))

        verifyNoMoreInteractions(connector)
      }
    }
  }

  "Calling an external service with service in its domain name" should {
    val auditUri = "http://some.service.gov.uk:80/self-assessment/data"

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "generate an audit event" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody   = None

      val response = ResponseData(
        body    = Data.pure("the response body"),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)

      whenAuditSuccess(connector)

      httpWithAudit.audit(request, Right(response))

      val dataEvent = verifyAndRetrieveEvent(connector)

      dataEvent.auditSource shouldBe httpWithAudit.appName
      dataEvent.auditType shouldBe outboundCallAuditType
    }
  }

  "Auditing the url /write/audit" should {
    val auditUri = "/write/audit"

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "not generate an audit event" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody   = None

      val response = ResponseData(
        body    = Data.pure("the response body"),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)

      httpWithAudit.audit(request, Right(response))

      verifyNoMoreInteractions(connector)
    }

    "not generate an audit event when an exception has been thrown" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody   = None

      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)
      httpWithAudit.audit(request, Left("An exception occurred when calling sendevent datastream"))

      verifyNoMoreInteractions(connector)
    }
  }

  "Auditing the url /write/audit/merged" should {
    val auditUri = "/write/audit/merged"

    implicit val hc: HeaderCarrier = HeaderCarrier()

    "not generate an audit event" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody   = None

      val response = ResponseData(
        body    = Data.pure("the response body"),
        status  = 200,
        headers = Map.empty
      )

      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)

      httpWithAudit.audit(request, Right(response))

      verifyNoMoreInteractions(connector)
    }

    "not generate an audit event when an exception has been thrown" in  {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)
      val requestBody   = None

      val request = httpWithAudit.buildRequest(getVerb, auditUri, Seq.empty, requestBody)
      httpWithAudit.audit(request, Left("An exception occured when calling sendevent datastream"))

      verifyNoMoreInteractions(connector)
    }
  }

  "caseInsensitiveMap" should {
    "comma separate values for duplicate keys" in {
      val connector     = mock[AuditConnector]
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
        "b"  -> "d"
      )

      val result = httpWithAudit.caseInsensitiveMap(headers)

      result shouldBe Map("aa" -> "a,b,c,d", "b" -> "d")
      result.get("aA") shouldBe Some("a,b,c,d")
      result.get("Aa") shouldBe Some("a,b,c,d")
      result.get("AA") shouldBe Some("a,b,c,d")
    }

    "preserve the case of the first header name when there are duplicates" in {
      val connector     = mock[AuditConnector]
      val httpWithAudit = new HttpWithAuditing(connector)

      val headers = Seq(
        "X-forwarded-for" -> "a",
        "X-forwarded-for" -> "b",
        "x-forwarded-for" -> "c",
        "X-FORWARDED-for" -> "d",
        "b"               -> "d"
      )

      val result = httpWithAudit.caseInsensitiveMap(headers)

      result shouldBe Map("X-forwarded-for" -> "a,b,c,d", "b" -> "d")
    }
  }

  class HttpWithAuditing(connector: AuditConnector) extends HttpAuditing {
    override val appName: String = "httpWithAuditSpec"

    override def auditConnector: AuditConnector = connector

    def auditRequestWithResponseF(
      verb     : String,
      url      : String,
      request  : RequestData,
      responseF: Future[ResponseData]
    )(implicit hc: HeaderCarrier): Unit =
      AuditingHook(verb, url"$url", request, responseF)(hc, global)

    var now_call_count = 0
    override def now(): Instant = {
      now_call_count = now_call_count + 1

      if (now_call_count == 1) requestDateTime
      else responseDateTime
    }

    def buildRequest(verb: String, url: String, headers: Seq[(String, String)], body: Option[Data[HookData]]): HttpRequest = {
      now_call_count = 1
      HttpRequest(verb, url, headers, body, requestDateTime)
    }
  }

  def whenAuditSuccess(connector: AuditConnector): Unit =
    when(connector.sendMergedEvent(any[MergedDataEvent])(any[HeaderCarrier], any[ExecutionContext]))
      .thenReturn(Future.successful(Success))

  def verifyAndRetrieveEvent(connector: AuditConnector): MergedDataEvent = {
    val captor = ArgCaptor[MergedDataEvent]
    verify(connector).sendMergedEvent(captor)(any[HeaderCarrier], any[ExecutionContext])
    captor.value
  }
}
