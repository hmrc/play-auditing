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

import org.scalatest.exceptions.TestFailedException
import org.scalatest.exceptions.StackDepthException
import uk.gov.hmrc.http.hooks.Body

import scala.language.implicitConversions

trait BodyValues {
 /**
   * Implicit conversion that adds a <code>value</code> method to <code>Option</code>.
   *
   * @param opt the <code>Option</code> on which to add the <code>value</code> method
   */
  implicit def convertOptionToValuable[T](body: Body[String])(implicit pos: org.scalactic.source.Position): Valuable[T] =
    new Valuable(body, pos)

  /**
   * Wrapper class that adds a <code>value</code> method to <code>Option</code>, allowing
   * you to make statements like:
   *
   * <pre class="stHighlight">
   * opt.value should be &gt; 9
   * </pre>
   *
   * @param opt An option to convert to <code>Valuable</code>, which provides the <code>value</code> method.
   */
  class Valuable[T](body: Body[String], pos: org.scalactic.source.Position) {

    def complete: String =
      body match {
        case Body.Complete(body)  => body
        case Body.Truncated(body) => throw new TestFailedException((_: StackDepthException) => Some("Body was truncated"), cause = None, pos)
        case Body.Omitted         => throw new TestFailedException((_: StackDepthException) => Some("Body was omitted"  ), cause = None, pos)
      }

    def truncated: String =
      body match {
        case Body.Complete(body)  => throw new TestFailedException((_: StackDepthException) => Some("Body was complete"), cause = None, pos)
        case Body.Truncated(body) => body
        case Body.Omitted         => throw new TestFailedException((_: StackDepthException) => Some("Body was omitted"  ), cause = None, pos)
      }

    def omitted: String =
      body match {
        case Body.Complete(body)  => throw new TestFailedException((_: StackDepthException) => Some("Body was complete"), cause = None, pos)
        case Body.Truncated(body) => throw new TestFailedException((_: StackDepthException) => Some("Body was truncated"  ), cause = None, pos)
        case Body.Omitted         => ""
      }
  }
}
