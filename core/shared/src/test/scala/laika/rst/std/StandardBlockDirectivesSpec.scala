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

package laika.rst.std

import cats.data.NonEmptySet
import laika.api.MarkupParser
import laika.api.builder.OperationConfig
import laika.ast.Path.Root
import laika.ast.RelativePath.CurrentTree
import laika.ast._
import laika.ast.sample.{ParagraphCompanionShortcuts, SampleTrees}
import laika.config.{ConfigValue, Field, LaikaKeys, ObjectValue, StringValue}
import laika.format.ReStructuredText
import laika.parse.GeneratedSource
import laika.rewrite.ReferenceResolver.CursorKeys
import laika.rewrite.{DefaultTemplatePath, TemplateContext, TemplateRewriter}
import laika.rewrite.link.LinkConfig
import laika.rst.ast.{Contents, Include, RstStyle}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * @author Jens Halm
 */
class StandardBlockDirectivesSpec extends AnyFlatSpec 
                                  with Matchers 
                                  with ParagraphCompanionShortcuts {

  val simplePars: List[Paragraph] = List(p("1st Para"), p("2nd Para"))

  private val parser = MarkupParser
    .of(ReStructuredText)
    .withConfigValue(LinkConfig(excludeFromValidation = Seq(Root)))
    .build
  
  def parseDoc (input: String): Document = parser.parse(input).toOption.get

  def parseRaw (input: String): RootElement = MarkupParser
    .of(ReStructuredText)
    .build
    .parseUnresolved(input)
    .toOption
    .get
    .document
    .content
    .rewriteBlocks({ case _: Hidden with Block => Remove }) // removing the noise of rst TextRoles which are not the focus of this spec

  def parse (input: String): RootElement = parseDoc(input).content
   
  def parseWithFragments (input: String): (Map[String, Element], RootElement) = {
    val doc = parseDoc(input)
    (doc.fragments, doc.content)
  }
  
  "The compound directive" should "parse a sequence of two paragraphs" in {
    val input = """.. compound::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(BlockSequence(simplePars, RstStyle.compound))
    parse(input) should be (result)
  }
  
  it should "allow to set an id for the sequence" in {
    val input = """.. compound::
      | :name: foo
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(BlockSequence(simplePars, RstStyle.compound + Id("foo")))
    parse(input) should be (result)
  }
  
  it should "allow to set a style for the sequence" in {
    val input = """.. compound::
      | :class: foo
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(BlockSequence(simplePars, Styles("foo", "compound")))
    parse(input) should be (result)
  }
  
  
  "The container directive" should "parse a sequence of two paragraphs" in {
    val input = """.. container::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(BlockSequence(simplePars))
    parse(input) should be (result)
  }
  
  it should "parse a sequence of two paragraphs with two custom styles" in {
    val input = """.. container:: foo bar
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(BlockSequence(simplePars, Styles("foo", "bar")))
    parse(input) should be (result)
  }
  
  
  "The admonition directives" should "parse a generic admonition" in {
    val input = """.. admonition:: TITLE
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("TITLE")), simplePars, RstStyle.admonition))
    parse(input) should be (result)
  }
  
  it should "allow to set id and style for the admonition" in {
    val input = """.. admonition:: TITLE
      | :name: foo
      | :class: bar
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("TITLE")), simplePars, Id("foo") + Styles("bar","admonition")))
    parse(input) should be (result)
  }
  
  it should "parse the attention admonition" in {
    val input = """.. attention::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Attention!")), simplePars, Styles("attention")))
    parse(input) should be (result)
  }
  
  it should "allow to set id and style for the attention admonition" in {
    val input = """.. attention::
      | :name: foo
      | :class: bar
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Attention!")), simplePars, Id("foo") + Styles("bar","attention")))
    parse(input) should be (result)
  }
  
  it should "correctly parse nested blocks and inline markup" in {
    val input = """.. attention::
      |
      | 1st *Para*
      |
      |  2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Attention!")), List(p(Text("1st "),Emphasized("Para")), QuotedBlock("2nd Para")), Styles("attention")))
    parse(input) should be (result)
  }
  
  it should "parse the caution admonition" in {
    val input = """.. caution::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Caution!")), simplePars, Styles("caution")))
    parse(input) should be (result)
  }
  
  it should "parse the danger admonition" in {
    val input = """.. danger::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("!DANGER!")), simplePars, Styles("danger")))
    parse(input) should be (result)
  }
  
  it should "parse the error admonition" in {
    val input = """.. error::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Error")), simplePars, Styles("error")))
    parse(input) should be (result)
  }
  
  it should "parse the hint admonition" in {
    val input = """.. hint::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Hint")), simplePars, Styles("hint")))
    parse(input) should be (result)
  }
  
  it should "parse the important admonition" in {
    val input = """.. important::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Important")), simplePars, Styles("important")))
    parse(input) should be (result)
  }
  
  it should "parse the note admonition" in {
    val input = """.. note::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Note")), simplePars, Styles("note")))
    parse(input) should be (result)
  }
  
  it should "parse the tip admonition" in {
    val input = """.. tip::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Tip")), simplePars, Styles("tip")))
    parse(input) should be (result)
  }
  
  it should "parse the warning admonition" in {
    val input = """.. warning::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Warning")), simplePars, Styles("warning")))
    parse(input) should be (result)
  }
  
  it should "match the name of the directive case-insensitively" in {
    val input = """.. Warning::
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("Warning")), simplePars, Styles("warning")))
    parse(input) should be (result)
  }
  
  
  "The topic directive" should "parse a sequence of two paragraphs" in {
    val input = """.. topic:: TITLE
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("TITLE")), simplePars, RstStyle.topic))
    parse(input) should be (result)
  }
  
  it should "allow to set id and style for the admonition" in {
    val input = """.. topic:: TITLE
      | :name: foo
      | :class: bar
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("TITLE")), simplePars, Id("foo") + Styles("bar","topic")))
    parse(input) should be (result)
  }
  
  
  "The sidebar directive" should "parse a sequence of two paragraphs" in {
    val input = """.. sidebar:: TITLE
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("TITLE")), simplePars, RstStyle.sidebar))
    parse(input) should be (result)
  }
  
  it should "allow to set id and style for the admonition" in {
    val input = """.. sidebar:: TITLE
      | :name: foo
      | :class: bar
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("TITLE")), simplePars, Id("foo") + Styles("bar","sidebar")))
    parse(input) should be (result)
  }
  
  it should "allow to set the id and a subtitle for the admonition" in {
    val input = """.. sidebar:: TITLE
      | :name: foo
      | :subtitle: some *text*
      |
      | 1st Para
      |
      | 2nd Para""".stripMargin
    val result = RootElement(TitledBlock(List(Text("TITLE")), Paragraph(List(Text("some "),Emphasized("text")), RstStyle.subtitle) :: simplePars, 
        Id("foo") + RstStyle.sidebar))
    parse(input) should be (result)
  }
  
  
  "The rubric directive" should "parse spans without other options" in {
    val input = """.. rubric:: some *text*"""
    val result = RootElement(Paragraph(List(Text("some "),Emphasized("text")), RstStyle.rubric))
    parse(input) should be (result)
  }
  
  it should "allow to set id and styles" in {
    val input = """.. rubric:: some *text*
      | :name: foo
      | :class: bar""".stripMargin
    val result = RootElement(Paragraph(List(Text("some "),Emphasized("text")), Id("foo") + Styles("bar","rubric")))
    parse(input) should be (result)
  }
  
  
  "The epigraph directive" should "parse a single line" in {
    val input = """.. epigraph:: some *text*"""
    val result = RootElement(QuotedBlock(List(p(Text("some "),Emphasized("text"))), Nil, Styles("epigraph")))
    parse(input) should be (result)
  }
  
  it should "parse multiple lines" in {
    val input = """.. epigraph:: 
      |
      | some *text*
      | some more""".stripMargin
    val result = RootElement(QuotedBlock(List(p(Text("some "),Emphasized("text"),Text("\nsome more"))), Nil, Styles("epigraph")))
    parse(input) should be (result)
  }
  
  it should "support attributions" in {
    val input = """.. epigraph:: 
      |
      | some *text*
      | some more
      | 
      | -- attr""".stripMargin
    val result = RootElement(QuotedBlock(List(p(Text("some "),Emphasized("text"),Text("\nsome more"))), List(Text("attr", Style.attribution)), Styles("epigraph")))
    parse(input) should be (result)
  }
  
  "The highlights directive" should "parse a single line" in {
    val input = """.. highlights:: some *text*"""
    val result = RootElement(QuotedBlock(List(p(Text("some "),Emphasized("text"))), Nil, Styles("highlights")))
    parse(input) should be (result)
  }
  
  "The pull-quote directive" should "parse a single line" in {
    val input = """.. pull-quote:: some *text*"""
    val result = RootElement(QuotedBlock(List(p(Text("some "),Emphasized("text"))), Nil, Styles("pull-quote")))
    parse(input) should be (result)
  }
  
  
  "The parsed-literal directive" should "parse multiple lines" in {
    val input = """.. parsed-literal::
      | 
      | some *text*
      | some more""".stripMargin
    val result = RootElement(ParsedLiteralBlock(List(Text("some "),Emphasized("text"),Text("\nsome more"))))
    parse(input) should be (result)
  }
  
  it should "allow to set id and style" in {
    val input = """.. parsed-literal::
      | :name: foo
      | :class: bar
      |
      | some *text*
      | some more""".stripMargin
    val result = RootElement(ParsedLiteralBlock(List(Text("some "),Emphasized("text"),Text("\nsome more")), Id("foo") + Styles("bar")))
    parse(input) should be (result)
  }
  
  it should "parse multiple lines separated by spaces, preserving indentation" in {
    val input = """.. parsed-literal::
      | 
      | some *text*
      | some more
      |
      |  indented""".stripMargin
    val result = RootElement(ParsedLiteralBlock(List(Text("some "),Emphasized("text"),Text("\nsome more\n\n indented"))))
    parse(input) should be (result)
  }
  
  
  "The code directive" should "parse multiple lines" in {
    val input = """.. code:: banana-script
      | 
      | some banana
      | some more""".stripMargin
    val result = RootElement(CodeBlock("banana-script", List(Text("some banana\nsome more"))))
    parse(input) should be (result)
  }
  
  it should "allow to set id and style" in {
    val input = """.. code:: banana-script
      | :name: foo
      | :class: bar
      |
      | some banana
      | some more""".stripMargin
    val result = RootElement(CodeBlock("banana-script", List(Text("some banana\nsome more")), Id("foo") + Styles("bar")))
    parse(input) should be (result)
  }
  
  it should "parse multiple lines separated by spaces, preserving indentation" in {
    val input = """.. code:: banana-script
      | 
      | some banana
      | some more
      |
      |  indented""".stripMargin
    val result = RootElement(CodeBlock("banana-script", List(Text("some banana\nsome more\n\n indented"))))
    parse(input) should be (result)
  }
  
  
  "The table directive" should "parse a grid table with caption" in {
    val input = """.. table:: *caption*
      | 
      | +---+---+
      | | a | b |
      | +---+---+
      | | c | d |
      | +---+---+""".stripMargin
    val result = RootElement(Table(Row(BodyCell("a"),BodyCell("b")), Row(BodyCell("c"),BodyCell("d"))).copy(caption = Caption(List(Emphasized("caption")))))
    parse(input) should be (result)
  }
  
  it should "parse a simple table with caption" in {
    val input = """.. table:: *caption*
      | 
      | ===  ===
      |  a    b
      |  c    d
      | ===  ===""".stripMargin
    val result = RootElement(Table(Row(BodyCell("a"),BodyCell("b")), Row(BodyCell("c"),BodyCell("d"))).copy(caption = Caption(List(Emphasized("caption")))))
    parse(input) should be (result)
  }
  
  it should "parse a simple table without caption" in {
    val input = """.. table::
      | 
      | ===  ===
      |  a    b
      |  c    d
      | ===  ===""".stripMargin
    val result = RootElement(Table(Row(BodyCell("a"),BodyCell("b")), Row(BodyCell("c"),BodyCell("d"))))
    parse(input) should be (result)
  }
  
  
  val imgTarget = InternalTarget(CurrentTree / "picture.jpg")
  val resolvedImageTarget = InternalTarget(CurrentTree / "picture.jpg")
  val imgLink = SpanLink.external("http://foo.com/")(Image(resolvedImageTarget))
  
  "The image directive" should "parse the URI argument" in {
    val input = """.. image:: picture.jpg"""
    val result = RootElement(p(Image(resolvedImageTarget)))
    parse(input) should be (result)
  }
  
  it should "support the alt option" in {
    val input = """.. image:: picture.jpg
      | :alt: alt""".stripMargin
    val result = RootElement(p(Image(resolvedImageTarget, alt = Some("alt"))))
    parse(input) should be (result)
  }
  
  it should "support the target option with a simple reference" in {
    val input = """.. image:: picture.jpg
      | :target: ref_
      |
      |.. _ref: http://foo.com/""".stripMargin
    val result = RootElement(p(imgLink))
    parse(input) should be (result)
  }
  
  it should "support the target option with a phrase reference" in {
    val input = """.. image:: picture.jpg
      | :target: `some ref`_
      |
      |.. _`some ref`: http://foo.com/""".stripMargin
    val result = RootElement(p(imgLink))
    parse(input) should be (result)
  }
  
  it should "support the target option with a uri" in {
    val input = """.. image:: picture.jpg
      | :target: http://foo.com/""".stripMargin
    val result = RootElement(p(imgLink))
    parse(input) should be (result)
  }
  
  it should "support the class option" in {
    val input = """.. image:: picture.jpg
      | :class: foo""".stripMargin
    val result = RootElement(p(Image(resolvedImageTarget, options=Styles("foo"))))
    parse(input) should be (result)
  }
  
  
  "The figure directive" should "parse the URI argument" in {
    val input = """.. figure:: picture.jpg"""
    val result = RootElement(Figure(Image(resolvedImageTarget),Nil,Nil))
    parse(input) should be (result)
  }
  
  it should "support a caption" in {
    val input = """.. figure:: picture.jpg
      |
      | This is the *caption*""".stripMargin
    val result = RootElement(Figure(Image(resolvedImageTarget), List(Text("This is the "),Emphasized("caption")), Nil))
    parse(input) should be (result)
  }
  
  it should "support a caption and a legend" in {
    val input = """.. figure:: picture.jpg
      |
      | This is the *caption*
      |
      | And this is the legend""".stripMargin
    val result = RootElement(Figure(Image(resolvedImageTarget), List(Text("This is the "),Emphasized("caption")), List(p("And this is the legend"))))
    parse(input) should be (result)
  }
  
  it should "support the class option for the figure and the image" in {
    val input = """.. figure:: picture.jpg
      | :class: img
      | :figclass: fig""".stripMargin
    val result = RootElement(Figure(Image(resolvedImageTarget, options=Styles("img")), Nil, Nil, Styles("fig")))
    parse(input) should be (result)
  }
  
  it should "support the target option with a simple reference and a caption" in {
    val input = """.. figure:: picture.jpg
      | :target: ref_
      |
      | This is the *caption*
      |
      |.. _ref: http://foo.com/""".stripMargin
    val result = RootElement(Figure(imgLink, List(Text("This is the "),Emphasized("caption")), Nil))
    parse(input) should be (result)
  }
  
  
  "The header directive" should "create a fragment in the document" in {
    val input = """.. header:: 
      | This is
      | a header
      |
      |This isn't""".stripMargin
    val fragments = Map("header" -> BlockSequence("This is\na header"))
    val rootElem = RootElement(p("This isn't"))
    parseWithFragments(input) should be ((fragments, rootElem))
  }
  
  "The footer directive" should "create a fragment in the document" in {
    val input = """.. footer:: 
      | This is
      | a footer
      |
      |This isn't""".stripMargin
    val fragments = Map("footer" -> BlockSequence("This is\na footer"))
    val rootElem = RootElement(p("This isn't"))
    parseWithFragments(input) should be ((fragments, rootElem))
  }
  
  "The include directive" should "create a placeholder in the document" in {
    val input = """.. include:: other.rst"""
    val expected = RootElement(Include("other.rst", GeneratedSource))
    parseRaw(input) should be (expected)
  }
  
  "The include rewriter" should "replace the node with the corresponding document" in {
    val ref = TemplateContextReference(CursorKeys.documentContent, required = true, GeneratedSource)
    val tree = SampleTrees.twoDocuments
      .doc1.content(Include("doc-2", GeneratedSource))
      .doc2.content(p("text"))
      .root.template(DefaultTemplatePath.forHTML.name, ref)
      .build
      .tree

    val result = for {
      rewrittenTree    <- tree.rewrite(OperationConfig.default.withBundlesFor(ReStructuredText).rewriteRulesFor(DocumentTreeRoot(tree)))
      templatesApplied <- TemplateRewriter.applyTemplates(DocumentTreeRoot(rewrittenTree), TemplateContext("html"))
    } yield templatesApplied.tree.content.collect { case doc: Document => doc }.head.content

    result should be (Right(RootElement(BlockSequence("text"))))
  }
  
  "The title directive" should "set the title in the document instance" in {
    val input = """.. title:: Title"""
    parseDoc(input).title should be (Some(SpanSequence("Title")))
  }
  
  "The meta directive" should "create config entries in the document instance" in {
    val input = """.. meta::
      | :key1: val1
      | :key2: val2""".stripMargin
    val config = ObjectValue(Seq(Field("key1", StringValue("val1")), Field("key2", StringValue("val2"))))
    parseDoc(input).config.get[ConfigValue]("meta") should be (Right(config))
  }
  
  "The sectnum directive" should "create config entries in the document instance" in {
    val input = """.. sectnum::
      | :depth: 3
      | :start: 1
      | :prefix: (
      | :suffix: )""".stripMargin
    val config = ObjectValue(Seq(
      Field("depth", StringValue("3")), 
      Field("start", StringValue("1")), 
      Field("prefix", StringValue("(")), 
      Field("suffix", StringValue(")"))
    ))
    parseDoc(input).config.get[ConfigValue](LaikaKeys.autonumbering) should be (Right(config))
  }
  
  "The contents directive" should "create a placeholder in the document" in {
    val input = """.. contents:: This is the title
      | :depth: 3
      | :local: true""".stripMargin
    val elem = Contents("This is the title", GeneratedSource, depth = 3, local = true)
    parseRaw(input) should be (RootElement(elem))
  }
  
  "The contents rewriter" should "replace the node with the corresponding list element" in {
    def header (level: Int, title: Int, style: String = "section") =
      Header(level,List(Text("Title "+title)),Id("title-"+title) + Styles(style))
      
    def title (title: Int) =
      Title(List(Text("Title "+title)),Id("title-"+title) + Style.title)
      
    def link (level: Int, titleNum: Int, children: Seq[NavigationItem] = Nil): NavigationItem = {
      val target = Some(NavigationLink(InternalTarget(Root / s"doc#title-$titleNum").relativeTo(Root / "doc")))
      val title = SpanSequence("Title "+titleNum)
      NavigationItem(title, children, target, options = Style.level(level))
    }

    val sectionsWithTitle = RootElement(
      header(1,1,"title") ::
      Contents("This is the title", GeneratedSource, 3) ::
      header(2,2) ::
      header(3,3) ::
      header(2,4) ::
      header(3,5) ::
      Nil
    )
    
    val navList = NavigationList(Seq(
      link(1, 2, Seq(link(2, 3))),
      link(1, 4, Seq(link(2, 5)))
    ))
    
    val expected = RootElement(
      title(1),
      TitledBlock(List(Text("This is the title")), List(navList), Style.nav),
      Section(header(2,2), List(Section(header(3,3), Nil))),
      Section(header(2,4), List(Section(header(3,5), Nil)))
    )
    
    val document = Document(Root / "doc", sectionsWithTitle)
    val template = TemplateDocument(DefaultTemplatePath.forHTML, 
      TemplateRoot(TemplateContextReference(CursorKeys.documentContent, required = true, GeneratedSource)))
    val tree = DocumentTree(Root, content = List(document), templates = List(template))
    val result = for {
      rewrittenTree    <- tree.rewrite(OperationConfig.default.withBundlesFor(ReStructuredText).rewriteRulesFor(DocumentTreeRoot(tree)))
      templatesApplied <- TemplateRewriter.applyTemplates(DocumentTreeRoot(rewrittenTree), TemplateContext("html"))
    } yield templatesApplied.tree.content.collect { case doc: Document => doc }.head.content
    result should be (Right(expected))
  }
  
  
  "The raw directive" should "support raw input with one format" in {
    val input = """.. raw:: format
      |
      | some input
      |
      | some more""".stripMargin
    val result = RootElement(RawContent(NonEmptySet.one("format"), "some input\n\nsome more"))
    MarkupParser.of(ReStructuredText).withRawContent.build.parse(input).toOption.get.content should be (result)
  }
  
  
}
