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

import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.ExecutionContext

trait FilterFlowMock {

  def actionNotFoundMessage = "404 Not Found"

  def nextAction(implicit ec: ExecutionContext): Action[AnyContent] = Action(NotFound(actionNotFoundMessage))

  def exceptionThrowingAction(implicit ec: ExecutionContext) = Action.async { request =>
    throw new RuntimeException("Something went wrong")
  }
}
