/*
 * Copyright 2012-2020 the original author or authors.
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

package laika.sbt

import laika.ast.RewriteRules.RewriteRulesBuilder
import laika.ast._
import laika.bundle.{ExtensionBundle, RenderOverrides}
import laika.format._
import laika.render.{FOFormatter, HTMLFormatter}

/** API shortcuts for the most common extension points that create
  * an extension bundle from a single feature, so that it can be passed
  * to the `laikaExtensions` setting.
  *
  * Example:
  *
  * {{{
  * laikaExtensions += laikaSpanRewriteRule {
  *   case Emphasized(content, _) => Replace(Strong(content))
  * }
  * }}}
  *
  * @author Jens Halm
  */
trait ExtensionBundles {

  /** Create an extension bundle based on the specified custom HTML render function.
    */
  def laikaHtmlRenderer (f: PartialFunction[(HTMLFormatter, Element), String]): ExtensionBundle = new ExtensionBundle {
    val description: String = "Custom HTML render function"
    override def renderOverrides: Seq[RenderOverrides] = Seq(HTML.Overrides(value = f))
  }

  /** Create an extension bundle based on the specified custom HTML render function.
    */
  def laikaEpubRenderer (f: PartialFunction[(HTMLFormatter, Element), String]): ExtensionBundle = new ExtensionBundle {
    val description: String = "Custom XHTML render function for EPUB"
    override def renderOverrides: Seq[RenderOverrides] = Seq(EPUB.XHTML.Overrides(value = f))
  }

  /** Create an extension bundle based on the specified custom XSL-FO render function.
    *
    * Such a render function will also be used for PDF rendering, as XSL-FO is an interim
    * format for the PDF renderer.
    */
  def laikaFoRenderer (f: PartialFunction[(FOFormatter, Element), String]): ExtensionBundle = new ExtensionBundle {
    val description: String = "Custom XSL-FO render function for PDF"
    override def renderOverrides: Seq[RenderOverrides] = Seq(XSLFO.Overrides(value = f))
  }

  /** Create an extension bundle based on the specified rewrite rule for spans.
    *
    * Rewrite rules allow the modification of the document AST between parse and render operations.
    */
  def laikaSpanRewriteRule (rule: RewriteRule[Span]): ExtensionBundle = laikaRewriteRuleBuilder(_ => Right(RewriteRules.forSpans(rule)))

  /** Create an extension bundle based on the specified rewrite rule for blocks.
    *
    * Rewrite rules allow the modification of the document AST between parse and render operations.
    */
  def laikaBlockRewriteRule (rule: RewriteRule[Block]): ExtensionBundle = laikaRewriteRuleBuilder(_ => Right(RewriteRules.forBlocks(rule)))

  @deprecated("use laikaRewriteRuleBuilder which includes error handling", "0.18.0")
  def laikaRewriteRuleFactory (factory: DocumentCursor => RewriteRules): ExtensionBundle = new ExtensionBundle {
    val description: String = "Custom rewrite rules"
    override def rewriteRules: Seq[RewriteRulesBuilder] = Seq(factory.andThen(Right.apply))
  }

  /** Create an extension bundle based on the specified rewrite rule.
    *
    * Rewrite rules allow the modification of the document AST between parse and render operations.
    * The supplied function will get invoked for every document in the transformation, therefore
    * creating a new rule for each document.
    */
  def laikaRewriteRuleBuilder (builder: RewriteRulesBuilder): ExtensionBundle = new ExtensionBundle {
    val description: String = "Custom rewrite rules"
    override def rewriteRules: Seq[RewriteRulesBuilder] = Seq(builder)
  }

  /** Create an extension bundle based on the specified document type matcher.
    *
    * The matcher function determines the document type of the input based on its path.
    */
  def laikaDocTypeMatcher (f: PartialFunction[laika.ast.Path, DocumentType]): ExtensionBundle = new ExtensionBundle {
    val description: String = "Custom document type matcher"
    override def docTypeMatcher: PartialFunction[laika.ast.Path, DocumentType] = f
  }

}
