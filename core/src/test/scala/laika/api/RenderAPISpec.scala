/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.api

import laika.ast._
import laika.ast.helper.ModelBuilder
import laika.format._
import org.scalatest.{FlatSpec, Matchers}

class RenderAPISpec extends FlatSpec 
                    with Matchers
                    with ModelBuilder { self =>

  
  val rootElem = root(p("aaö"), p("bbb"))

  val expected = """RootElement - Blocks: 2
      |. Paragraph - Spans: 1
      |. . Text - 'aaö'
      |. Paragraph - Spans: 1
      |. . Text - 'bbb'""".stripMargin

  "The Render API" should "render a document to a string" in {
    (Render as AST render rootElem) should be (expected)
  }

  it should "allow to override the default renderer for specific element types" in {
    val render = Render as AST rendering { case (_, Text(content,_)) => s"String - '$content'" }
    val modifiedResult = expected.replaceAllLiterally("Text", "String")
    (render render rootElem) should be (modifiedResult)
  }

}
  