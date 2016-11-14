/*
 * Copyright 2016 HM Revenue & Customs
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

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import controllers.Assets
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{Matchers, TestData, WordSpecLike}
import org.scalatestplus.play._
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WS
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.{FakeApplication, FakeRequest}
import uk.gov.hmrc.play.audit.http.connector.MockAuditConnector
import uk.gov.hmrc.play.audit.model.{DataEvent, DeviceFingerprint}
import uk.gov.hmrc.play.audit.{EventKeys, EventTypes}
import uk.gov.hmrc.play.http.{CookieNames, HeaderCarrier, HeaderNames}
import uk.gov.hmrc.play.test.Http._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class FrontendAuditFilterSpec extends WordSpecLike with Matchers with Eventually with ScalaFutures with FilterFlowMock with MockitoSugar {

  val filter = new FrontendAuditFilter {

    override val maskedFormFields: Seq[String] = Seq("password")

    override val applicationPort: Option[Int] = Some(80)

    override val auditConnector = new MockAuditConnector

    override val appName: String = "app"

    override def controllerNeedsAuditing(controllerName: String): Boolean = false

    implicit val system = ActorSystem("test")
    implicit override def mat: Materializer = ActorMaterializer()
  }

  "A password" should {
    "be obfuscated with the password at the beginning" in {
      filter.stripPasswords(Some("application/x-www-form-urlencoded"), "password=p2ssword%26adkj&csrfToken=123&userId=113244018119", Seq("password")) shouldBe "password=#########&csrfToken=123&userId=113244018119"
    }

    "be obfuscated with the password in the end" in {
      filter.stripPasswords(Some("application/x-www-form-urlencoded"), "csrfToken=123&userId=113244018119&password=p2ssword%26adkj", Seq("password")) shouldBe "csrfToken=123&userId=113244018119&password=#########"
    }

    "be obfuscated with the password in the middle" in {
      filter.stripPasswords(Some("application/x-www-form-urlencoded"), "csrfToken=123&password=p2ssword%26adkj&userId=113244018119", Seq("password")) shouldBe "csrfToken=123&password=#########&userId=113244018119"
    }

    "be obfuscated even if the password is empty" in {
      filter.stripPasswords(Some("application/x-www-form-urlencoded"), "csrfToken=123&password=&userId=113244018119", Seq("password")) shouldBe "csrfToken=123&password=#########&userId=113244018119"
    }

    "not be obfuscated if content type is not application/x-www-form-urlencoded" in {
      filter.stripPasswords(Some("text/json"), "{ password=p2ssword%26adkj }", Seq("password")) shouldBe "{ password=p2ssword%26adkj }"
    }

    "be obfuscated using multiple fields" in {
      val body = """companyNumber=05448736&password=secret&authCode=code"""
      val result = filter.stripPasswords(Some("application/x-www-form-urlencoded"), body, Seq("password", "authCode"))

      result shouldBe """companyNumber=05448736&password=#########&authCode=#########"""
    }
  }

  "The Filter" should {

    "generate audit events without passwords" when {
      val body = "csrfToken=acb" +
        "&userId=113244018119" +
        "&password=123456789" +
        "&key1="

      val source = Source.single(ByteString(body))
      val request = FakeRequest("POST", "/foo").withHeaders("Content-Type" -> "application/x-www-form-urlencoded")

      "when the request succeeds" in {
        val result = await(filter.apply(nextAction)(request).run(source))
        await(enumerateResponseBody(result))
        behave like expected
      }

      "when an action further down the chain throws an exception" in {
        a[RuntimeException] should be thrownBy await(filter.apply(exceptionThrowingAction)(request).run(source))
        behave like expected
      }

      def expected() = eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.auditType shouldBe EventTypes.RequestReceived
        event.detail should contain("requestBody" -> "csrfToken=acb&userId=113244018119&password=#########&key1=")
      }(PatienceConfig(Span(5, Seconds), Span(200, Millis)))
    }

    "generate audit events with the device finger print when it is supplied in a request cookie" when {

      val encryptedFingerprint = "eyJ1c2VyQWdlbnQiOiJNb3ppbGxhLzUuMCAoTWFjaW50b3NoOyBJbnRlbCBNYWMgT1MgWCAxMF84XzUpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGx" +
        "pa2UgR2Vja28pIENocm9tZS8zMS4wLjE2NTAuNDggU2FmYXJpLzUzNy4zNiIsImxhbmd1YWdlIjoiZW4tVVMiLCJjb2xvckRlcHRoIjoyNCwicmVzb2x1dGlvbiI6IjgwMHgxMj" +
        "gwIiwidGltZXpvbmUiOjAsInNlc3Npb25TdG9yYWdlIjp0cnVlLCJsb2NhbFN0b3JhZ2UiOnRydWUsImluZGV4ZWREQiI6dHJ1ZSwicGxhdGZvcm0iOiJNYWNJbnRlbCIsImRvT" +
        "m90VHJhY2siOnRydWUsIm51bWJlck9mUGx1Z2lucyI6NSwicGx1Z2lucyI6WyJTaG9ja3dhdmUgRmxhc2giLCJDaHJvbWUgUmVtb3RlIERlc2t0b3AgVmlld2VyIiwiTmF0aXZl" +
        "IENsaWVudCIsIkNocm9tZSBQREYgVmlld2VyIiwiUXVpY2tUaW1lIFBsdWctaW4gNy43LjEiXX0="

      val request = FakeRequest("GET", "/foo").withCookies(Cookie(DeviceFingerprint.deviceFingerprintCookieName, encryptedFingerprint))

      "when the request succeeds" in {
        val result = await(filter.apply(nextAction)(request).run)
        await(enumerateResponseBody(result))

        behave like expected
      }

      "when an action further down the chain throws an exception" in {
        a[RuntimeException] should be thrownBy await(filter.apply(exceptionThrowingAction)(request).run)
        behave like expected
      }

      def expected() = eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.auditType shouldBe EventTypes.RequestReceived
        event.detail should contain("deviceFingerprint" -> (
          """{"userAgent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.48 Safari/537.36",""" +
            """"language":"en-US","colorDepth":24,"resolution":"800x1280","timezone":0,"sessionStorage":true,"localStorage":true,"indexedDB":true,"platform":"MacIntel",""" +
            """"doNotTrack":true,"numberOfPlugins":5,"plugins":["Shockwave Flash","Chrome Remote Desktop Viewer","Native Client","Chrome PDF Viewer","QuickTime Plug-in 7.7.1"]}""")
        )
      }
    }

    "generate audit events without the device finger print when it is not supplied in a request cookie" when {
      val request = FakeRequest("GET", "/foo")

      "when the request succeeds" in {
        val result = await(filter.apply(nextAction)(request).run)
        await(enumerateResponseBody(result))

        behave like expected
      }

      "when an action further down the chain throws an exception" in {
        a[RuntimeException] should be thrownBy await(filter.apply(exceptionThrowingAction)(request).run)
        behave like expected
      }

      def expected() = eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.auditType shouldBe EventTypes.RequestReceived
        event.detail should contain("deviceFingerprint" -> "-")
      }
    }

    "generate audit events without the device finger print when the value supplied in the request cookie is invalid" when {
      def request = FakeRequest("GET", "/foo").withCookies(Cookie(DeviceFingerprint.deviceFingerprintCookieName, "THIS IS SOME JUST THAT SHOULDN'T BE DECRYPTABLE *!@&£$)B__!@£$"))

      "when the request succeeds" in running(FakeApplication(additionalConfiguration = Map("play.http.cookies.strict" -> false))) {
        await(filter.apply(nextAction)(request).run)
        behave like expected
      }

      "when an action further down the chain throws an exception" in running(FakeApplication(additionalConfiguration = Map("play.http.cookies.strict" -> false))) {
        a[RuntimeException] should be thrownBy await(filter.apply(exceptionThrowingAction)(request).run)
        behave like expected
      }

      def expected() = eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.auditType shouldBe EventTypes.RequestReceived
        event.detail should contain("deviceFingerprint" -> "-")
      }
    }

    "use the session to read Authorization, session Id and token" when {

      "when the request succeeds" in running(FakeApplication()) {
        val request = FakeRequest("GET", "/foo").withSession("token" -> "aToken", "authToken" -> "Bearer fNAao9C4kTby8cqa6g75emw1DZIyA5B72nr9oKHHetE=",
          "sessionId" -> "mySessionId")

        val result = await(filter.apply(nextAction)(request).run)
        await(enumerateResponseBody(result))

        behave like expected
      }

      "when an action further down the chain throws an exception" in running(FakeApplication()) {
        val request = FakeRequest("GET", "/foo").withSession("token" -> "aToken", "authToken" -> "Bearer fNAao9C4kTby8cqa6g75emw1DZIyA5B72nr9oKHHetE=",
          "sessionId" -> "mySessionId")

        a[RuntimeException] should be thrownBy await(filter.apply(exceptionThrowingAction)(request).run)
        behave like expected
      }

      def expected() = eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.auditType shouldBe EventTypes.RequestReceived
        event.detail should contain("Authorization" -> "Bearer fNAao9C4kTby8cqa6g75emw1DZIyA5B72nr9oKHHetE=")
        event.detail should contain("token" -> "aToken")
        event.tags should contain("X-Session-ID" -> "mySessionId")
      }
    }

    "add the Location header to the details if available" in {
      implicit val hc = new HeaderCarrier()

      val next = Action.async { r =>
        Future.successful(Results.Ok.withHeaders("Location" -> "some url"))
      }

      val result = await(filter.apply(next)(FakeRequest()).run)
      await(enumerateResponseBody(result))

      eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.detail should contain("Location" -> "some url")
      }
    }

    "generate audit events with the device ID when it is supplied in a request cookie" when {
      val deviceID = "A_DEVICE_ID"

      val request = FakeRequest("GET", "/foo").withCookies(Cookie(CookieNames.deviceID, deviceID))

      "when the request succeeds" in {
        val result = await(filter.apply(nextAction)(request).run)
        await(enumerateResponseBody(result))

        behave like expected
      }

      "when an action further down the chain throws an exception" in {
        a[RuntimeException] should be thrownBy await(filter.apply(exceptionThrowingAction)(request).run)
        behave like expected
      }

      def expected() = eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.auditType shouldBe EventTypes.RequestReceived
        event.detail should contain("deviceID" -> deviceID)
      }
    }

    "generate audit events with the device ID from headers if not supplied as a cookie" when {
      val deviceID = "A_DEVICE_ID"

      val request = FakeRequest("GET", "/foo").withHeaders(HeaderNames.deviceID -> deviceID)

      "when the request succeeds" in {
        await(filter.apply(nextAction)(request).run)
        behave like expected
      }

      "when an action further down the chain throws an exception" in {
        a[RuntimeException] should be thrownBy await(filter.apply(exceptionThrowingAction)(request).run)
        behave like expected
      }

      def expected() = eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.auditType shouldBe EventTypes.RequestReceived
        event.detail should contain("deviceID" -> deviceID)
      }
    }

  }

  "Get query string for audit" should {

    "handle a simple querystring" in {
      filter.getQueryString(FakeRequest("GET", "/foo?action=frog").queryString) should be("action:frog")
    }

    "handle an empty querystring" in {
      filter.getQueryString(FakeRequest("GET", "/foo").queryString) should be("-")
    }

    "handle an invalid Request object" in {
      filter.getQueryString(FakeRequest("GET", "").queryString) should be("-")
    }

    "handle multiple query strings" in {
      filter.getQueryString(FakeRequest("GET", "/foo?action1=frog1&action2=frog2").queryString) should be("action1:frog1&action2:frog2")
    }

    "handle sequences of values for a single query string" in {
      filter.getQueryString(FakeRequest("GET", "/foo?action1=frog1,frog2").queryString) should be("action1:frog1,frog2")
    }

    "handle sequences of values with multiple query strings" in {
      val underOrderedProcessedQueryString = filter.getQueryString(FakeRequest("GET", "/foo?mammal=dog,cat&bird=dove&reptile=lizard,snake").queryString)
      underOrderedProcessedQueryString should be("mammal:dog,cat&bird:dove&reptile:lizard,snake")
    }

    "handle empty maps" in {
      filter.getQueryString(Map.empty) should be("-")
    }

    "handle empty sequences" in {
      filter.getQueryString(Map("mammal" -> Seq.empty)) should be("mammal:")
    }

    "print in the same order as the sequence" in {
      filter.getQueryString(Map("mammal" -> Seq("dog", "cat"), "reptile" -> Seq("snake", "lizard"))) should be("mammal:dog,cat&reptile:snake,lizard")
    }
  }

  "Retrieve host from request" should {
    "convert a not found value into a hyphen" in {
      filter.getHost(FakeRequest()) should be("-")
    }

    "keep the host name when it does not contain any port" in {
      filter.getHost(FakeRequest().withHeaders("Host" -> "localhost")) should be("localhost")
    }

    "remove the port and keep host name when the host contains the port" in {
      filter.getHost(FakeRequest().withHeaders("Host" -> "localhost:9000")) should be("localhost")
    }

  }

  "Retrieve port from play configuration" should {

    "retrieve the port when it is specified in the configuration" in {
      filter.getPort should be("80")
    }

  }

  "A frontend response" should {
    "not be included in the audit message if it is HTML" in {
      implicit val hc = new HeaderCarrier()
      val next = Action(Results.Ok("....the response...").withHeaders("Content-Type" -> "text/html"))

      val result = await(filter.apply(next)(FakeRequest()).run)
      await(enumerateResponseBody(result))

      eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.detail should contain("responseMessage" -> "<HTML>...</HTML>")
      }
    }

    "not be included in the audit message if it is html with utf-8" in {
      implicit val hc = new HeaderCarrier()
      val next = Action.async { r =>
        Future.successful(Results.Ok("....the response...").withHeaders("Content-Type" -> "text/html; charset=utf-8"))
      }

      val result = await(filter.apply(next)(FakeRequest()).run)
      await(enumerateResponseBody(result))

      eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.detail should contain("responseMessage" -> "<HTML>...</HTML>")
      }
    }


    "be included if the ContentType is not text/html" in {
      implicit val hc = new HeaderCarrier()
      val next = Action.async {
        r => Future.successful(Results.Status(303)("....the response...").withHeaders("Content-Type" -> "application/json"))
      }

      val result = await(filter.apply(next)(FakeRequest()).run)
      await(enumerateResponseBody(result))

      eventually {
        val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
        event.detail should contain("responseMessage" -> "....the response...")
      }
    }
  }
}

class FrontendAuditFilterServerSpec extends FrontendAuditFilterSpec with OneServerPerTest {

  val random = new scala.util.Random
  val rs = randomString("abcdefghijklmnopqrstuvwxyz0123456789")(filter.maxBodySize * 3)
  val pc = PatienceConfig(Span(5, Seconds), Span(15, Millis))

  // Generate a random string of length n from the given alphabet
  def randomString(alphabet: String)(n: Int): String =
  Stream.continually(random.nextInt(alphabet.size)).map(alphabet).take(n).mkString

  val assets = new GuiceApplicationBuilder().injector().instanceOf[Assets]

  override def newAppForTest(testData: TestData): Application = new GuiceApplicationBuilder().routes({
    case ("GET", "/assets/stylesheet.css") => filter.apply(assets.at("/", "stylesheet.css"))
    case ("GET", "/assets/stylesheetLarge.css") => filter.apply(assets.at("/", "stylesheetLarge.css"))
    case ("GET", "/longresponse") => filter.apply(Action {
      Results.Ok(rs)
    })
    case ("POST", "/longrequest") => filter.apply(Action {
      Results.Ok
    })
  }).build()

  "Attempting to audit a large in-memory response" in {
    filter.auditConnector.reset

    val url = s"http://localhost:$port/longresponse"
    val response = await(WS.url(url).get())

    eventually {
      val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
      response.body.length should equal(rs.length)
      event.detail should not be null
      event.detail.get(EventKeys.ResponseMessage).getOrElse("").length should equal(filter.maxBodySize)
    }
  }

  "Attempting to audit assets" in {
    filter.auditConnector.reset

    val url = s"http://localhost:$port/assets/stylesheet.css"
    val response = await(WS.url(url).get())

    eventually {
      response.body.length should equal(10278)
      val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
      event.detail should not be null
      event.detail.get(EventKeys.ResponseMessage).getOrElse("").length should equal(10278)
    }
  }

  "Attempting to audit truncated large assets" in {
    filter.auditConnector.reset

    val url = s"http://localhost:$port/assets/stylesheetLarge.css"
    val response = await(WS.url(url).get())

    eventually {
      response.body.length should equal(45423)
      val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
      event.detail should not be null
      event.detail.get(EventKeys.ResponseMessage).getOrElse("").length should equal(filter.maxBodySize)
    }
  }

  "Attempting to audit a large request" in {
    filter.auditConnector.reset

    val url = s"http://localhost:$port/longrequest"
    val response = await(WS.url(url).post(rs))

    eventually {
      val event = filter.auditConnector.recordedEvent.get.asInstanceOf[DataEvent]
      event.detail should not be null
      event.detail.get(EventKeys.RequestBody).getOrElse("").length should equal(filter.maxBodySize)
    }(pc)
  }

}
