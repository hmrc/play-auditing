/*
 * Copyright 2023 HM Revenue & Customs
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

import akka.stream.Materializer
import org.slf4j.{Logger, LoggerFactory}
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.JsValue
import uk.gov.hmrc.audit.handler.{AuditHandler, DatastreamHandler, LoggingHandler}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.audit.{HandlerResult, WSClient}

import scala.concurrent.duration.{Duration,DurationInt}
import scala.concurrent.{ExecutionContext, Future}

trait AuditChannel {
  def auditingConfig   : AuditingConfig
  def materializer     : Materializer
  def lifecycle        : ApplicationLifecycle
  def datastreamMetrics: DatastreamMetrics

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  val defaultConnectionTimeout: Duration = 5000.millis
  val defaultRequestTimeout   : Duration = 5000.millis

  val defaultBaseUri = BaseUri("datastream.protected.mdtp", 90, "http")

  lazy val consumer: Consumer = auditingConfig.consumer.getOrElse(Consumer(defaultBaseUri))

  lazy val baseUri: BaseUri = consumer.baseUri

  private lazy val wsClient: WSClient = {
    implicit val m = materializer
    val wsClient = WSClient(
      connectTimeout = defaultConnectionTimeout,
      requestTimeout = defaultRequestTimeout,
      userAgent      = auditingConfig.auditSource
    )
    lifecycle.addStopHook { () =>
      logger.info("Closing play-auditing http connections...")
      wsClient.close()
      Future.unit
    }
    wsClient
  }

  def datastreamHandler(path: String): AuditHandler =
    new DatastreamHandler(
      baseUri.protocol,
      baseUri.host,
      baseUri.port,
      path,
      wsClient,
      datastreamMetrics
    )

  lazy val loggingConnector: AuditHandler = LoggingHandler

  def send(path:String, event: JsValue)(implicit ec: ExecutionContext): Future[HandlerResult] =
    datastreamHandler(path).sendEvent(event)
      .flatMap {
        case HandlerResult.Failure => loggingConnector.sendEvent(event)
        case result                => Future.successful(result)
      }
      .recover {
        case e: Throwable =>
          logger.error("Error in handler code", e)
          HandlerResult.Failure
      }
}
