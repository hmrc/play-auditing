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

package uk.gov.hmrc.play.audit.http.connector

import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.ArgumentMatcher
import org.mockito.Matchers._
import org.mockito.Matchers.{eq => meq}
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Seconds, Span}
import org.slf4j.Logger
import play.api.LoggerLike
import play.api.libs.json.{JsObject, JsValue, Json, Writes}
import uk.gov.hmrc.play.audit.EventTypes
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}
import uk.gov.hmrc.http.{CorePost, HeaderCarrier, HttpReads, HttpResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class ResponseFormatterSpec extends WordSpec with Matchers with ResponseFormatter {
  "checkResponse" should {
    "return None for any response code less than 300" in {
      val body = Json.obj("key" -> "value")

      (0 to 299).foreach { code =>
        val response = new HttpResponse {
          override val status = code
        }
        checkResponse(body, response) shouldBe None
      }
    }

    "Return Some message for a response code of 300 or above" in {
      val body = Json.obj("key" -> "value")

      (300 to 599).foreach { code =>
        val response = new HttpResponse {
          override val status = code
        }
        val result = checkResponse(body, response)
        result shouldNot be(None)

        val message = result.get
        message should startWith(AuditEventFailureKeys.LoggingAuditFailureResponseKey)
        message should include(body.toString)
        message should include(code.toString)
      }
    }
  }

  "makeFailureMessage" should {
    "make a message containing the body and the right logging key" in {
      val body: JsObject = Json.obj("key" -> "value")
      val message: String = makeFailureMessage(body)
      message should startWith(AuditEventFailureKeys.LoggingAuditRequestFailureKey)
      message should include(body.toString)
    }
  }

  private def checkAuditFailureMessage(message: String, body: JsValue, code: Int) {
    message should startWith(AuditEventFailureKeys.LoggingAuditFailureResponseKey)
    message should include(body.toString)
    message should include(code.toString)
  }
}

class ResultHandlerSpec extends WordSpec
                       with ShouldMatchers
                       with ResultHandler
                       with MockitoSugar
                       with ScalaFutures
                       with LoggerProvider {

  implicit override val patienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(5, Millis))

  val mockLogger = mock[Logger]
  when(mockLogger.isWarnEnabled()).thenReturn(true)
  override val logger = mockLogger

  "handleResult" should {
    "not log any error or for a result status of 200" in {
      val body = Json.obj("key" -> "value")

      val response = new HttpResponse {
        override val status = 200
      }

      handleResult(Future.successful(response), body)

      verifyNoMoreInteractions(mockLogger)
    }

    class IsValidFailureMessage(startsWith: String, contains: String*) extends ArgumentMatcher[String] {
      override def matches(item: scala.Any): Boolean = {
        val message = item.asInstanceOf[String]
        message.startsWith(startsWith) && contains.forall(message.contains)
      }
    }

    "log an error for a result status of 300" in {
      val body = Json.obj("key" -> "value")

      val code = 300
      val response = new HttpResponse {
        override val status = code
      }

      val f = Future.successful(response)
      handleResult(f, body).failed.futureValue

      val isValidFailureMessage = new IsValidFailureMessage(AuditEventFailureKeys.LoggingAuditFailureResponseKey,
                                                            body.toString(), code.toString)

      verify(mockLogger, times(1)).warn(argThat(isValidFailureMessage))
    }

    "log an error for a Future.failed" in {
      val body = Json.obj("key" -> "value")

      val f = Future.failed(new Exception("failed"))
      handleResult(f, body).failed.futureValue

      val isValidFailureMessage = new IsValidFailureMessage(AuditEventFailureKeys.LoggingAuditRequestFailureKey,
                                                            body.toString())
      verify(mockLogger).warn(argThat(isValidFailureMessage), any())
    }
  }
}

class AuditConnectorSpec extends WordSpecLike with MustMatchers with ScalaFutures with MockitoSugar with OneInstancePerTest {
  import AuditResult._

  import scala.concurrent.ExecutionContext.Implicits.global

  val eventTypes = new EventTypes {}

  val fakeConfig = AuditingConfig(consumer = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))),
                                      enabled = true,
                                      traceRequests = true)

  trait MockHttp extends CorePost {
    def response: Future[HttpResponse]
    var postedBody: String = ""

    def POSTString[O](url: String, body: String, headers: Seq[(String, String)])(implicit rds: HttpReads[O], hc: HeaderCarrier, ec: ExecutionContext): Future[O] = {
      postedBody = body
      response.asInstanceOf[Future[O]]
    }

    override def POST[I, O](url: String, body: I, headers: Seq[(String, String)])(implicit wts: Writes[I], rds: HttpReads[O], hc: HeaderCarrier, ec: ExecutionContext): Future[O] = ???
    override def POSTForm[O](url: String, body: Map[String, Seq[String]])(implicit rds: HttpReads[O], hc: HeaderCarrier, ec: ExecutionContext): Future[O] = ???
    override def POSTEmpty[O](url: String)(implicit rds: HttpReads[O], hc: HeaderCarrier, ec: ExecutionContext): Future[O] = ???
  }

  def createConnector(res: Future[HttpResponse], config: AuditingConfig = fakeConfig) = new AuditConnector with MockHttp {
    override def auditingConfig: AuditingConfig = config

    override val logger: Logger = mock[Logger]

    override def response: Future[HttpResponse] = res
  }

  val mockResponse: HttpResponse = mock[HttpResponse]

  "sendLargeMergedEvent" should {
    "call datastream with large merged event" taggedAs Tag("txm80") in {
      when(mockResponse.status).thenReturn(200)
      val response = Future.successful(mockResponse)

      val mergedEvent = MergedDataEvent("Test", "Test", "TestEventId",
          DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)),
          DataCall(Map.empty, Map.empty, DateTime.now(DateTimeZone.UTC)))

      val expected = Json.toJson(mergedEvent)

      val connector = createConnector(response)
      connector.sendLargeMergedEvent(mergedEvent).futureValue mustBe Success
    }
  }

  "sendEvent" should {
    val event = DataEvent("source", "type")
    val expected: JsValue = Json.toJson(event)

    "call datastream with the event converted to json" in {
      when(mockResponse.status).thenReturn(200)

      val connector = createConnector(Future.successful(mockResponse))
      connector.sendEvent(event).futureValue mustBe AuditResult.Success
    }

    "return a failed future if the HTTP response status is greater than 299" in {
      when(mockResponse.status).thenReturn(300)
      val connector = createConnector(Future.successful(mockResponse))

      val failureResponse = connector.sendEvent(event).failed.futureValue
      failureResponse must have ('nested (None))
      checkAuditFailureMessage(failureResponse.getMessage, Json.toJson(event), 300)
    }

    "return a failed future if there is an exception in the HTTP connection" in {
      val exception = new Exception("failed")
      val connector = createConnector(Future.failed(exception))

      val failureResponse = connector.sendEvent(event).failed.futureValue
      failureResponse must have ('nested (Some(exception)))
      checkAuditRequestFailureMessage(failureResponse.getMessage, Json.toJson(event))
    }

    "return disabled if auditing is not enabled" in {
      val disabledConfig = AuditingConfig(consumer = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))),
                                          enabled = false,
                                          traceRequests = true)

      when(mockResponse.status).thenReturn(200)
      val connector = createConnector(Future.successful(mockResponse), disabledConfig)
      connector.sendEvent(event).futureValue must be (AuditResult.Disabled)
    }

    "serialize the date correctly" in {
      val event: DataEvent = DataEvent("source", "type", generatedAt = new DateTime(0, DateTimeZone.UTC))
      val json: JsValue = Json.toJson(event)

      (json \ "generatedAt").as[String] mustBe "1970-01-01T00:00:00.000+0000"
    }

    "call data stream with extended event data converted to json" in {
//      when(mockResponse.status).thenReturn(200)
//
//      val detail = Json.parse( """{"some-event": "value", "some-other-event": "other-value"}""")
//      val event: ExtendedDataEvent = ExtendedDataEvent(auditSource = "source", auditType = "type", detail = detail)
//
//      val connector = createConnector(Future.successful(mockResponse)
//      when(mockRequestHolder.post(meq(Json.toJson(event)))(any())).thenReturn(Future.successful(mockResponse))
//
//      mockConnector(fakeConfig).sendEvent(event).futureValue mustBe AuditResult.Success

      fail("Pending AuditConnector changes to take constructor dependencies (Pete)")
    }
  }

  private def checkAuditRequestFailureMessage(message: String, body: JsValue) {
    message must startWith(AuditEventFailureKeys.LoggingAuditRequestFailureKey)
    message must include(body.toString)
  }

  private def checkAuditFailureMessage(message: String, body: JsValue, code: Int) {
    message must startWith(AuditEventFailureKeys.LoggingAuditFailureResponseKey)
    message must include(body.toString)
    message must include(code.toString)
  }
}
