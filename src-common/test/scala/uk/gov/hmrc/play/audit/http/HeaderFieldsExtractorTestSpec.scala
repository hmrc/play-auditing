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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class HeaderFieldsExtractorTestSpec
  extends AnyWordSpecLike
  with Matchers {

  "The optional audit fields code" should {

    "Return only surrogate header" in {
      val optionalFields =
        HeaderFieldsExtractor.optionalAuditFieldsSeq(Map("Foo" -> "Bar", "Ehh" -> "Meh", "Surrogate" -> "Cool").mapValues(Seq(_)).toMap)
      optionalFields shouldBe Map("surrogate" -> "Cool")
    }

    "Return no surrogate headers when none in headers" in {
      val optionalFields = HeaderFieldsExtractor.optionalAuditFieldsSeq(Map("Foo" -> "Bar", "Ehh" -> "Meh").mapValues(Seq(_)).toMap)
      optionalFields shouldBe empty
    }
  }
}
