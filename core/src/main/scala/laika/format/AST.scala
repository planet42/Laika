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

package laika.format

import laika.ast._
import laika.config.RenderConfig
import laika.factory.RenderFormat
import laika.io.Output
import laika.render.{ASTRenderer, TextWriter}

/** A renderer for AST output (a formatted Abstract Syntax Tree), primarily useful for debugging purposes.
 *  May be directly passed to the `Render` or `Transform` APIs:
 * 
 *  {{{
 *  Render as AST from document toString
 *  
 *  Transform from Markdown to AST fromFile "hello.md" toConsole
 *  }}}
 * 
 *  @author Jens Halm
 */
object AST extends RenderFormat[TextWriter] {

  val fileSuffix = "txt"
  
  def newRenderer (output: Output, root: Element, render: Element => Unit,
                   styles: StyleDeclarationSet, config: RenderConfig): (TextWriter, Element => Unit) = {

    val writer = new TextWriter(output asFunction, render, root, ". ")
    val renderer = new ASTRenderer(writer)

    (writer, renderer.render)
  }
  
  val defaultTheme = Theme()

}
