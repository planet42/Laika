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

package laika.config

import laika.api.MarkupParser
import laika.api.builder.OperationConfig
import laika.ast.Path.Root
import laika.ast._
import laika.ast.sample.TestSourceBuilders
import laika.bundle._
import laika.factory.MarkupFormat
import laika.parse._
import laika.parse.combinator.Parsers
import laika.parse.css.CSSParsers
import laika.parse.directive.ConfigHeaderParser
import laika.parse.markup.DocumentParser.DocumentInput
import laika.parse.text.TextParsers
import laika.parse.builders._
import laika.parse.implicits._
import laika.rewrite.ReferenceResolver.CursorKeys
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
  * @author Jens Halm
  */
class ParserBundleSpec extends AnyWordSpec with Matchers {


  trait SetupBase { self =>

    def parserBundles: Seq[ExtensionBundle]

    def blockParsers: Seq[BlockParserBuilder] = Nil
    def spanParsers: Seq[SpanParserBuilder] = Nil

    object Parser extends MarkupFormat {
      val fileSuffixes = Set("foo")
      val blockParsers = self.blockParsers
      val spanParsers = self.spanParsers
      lazy val extensions = parserBundles
    }

    val docConfig = {
      val base = ConfigBuilder.withOrigin(Origin(Origin.DocumentScope, Root / "doc")).build
      ConfigBuilder.withFallback(base).build // TODO - why is this nested twice?
    }
  }

  trait BundleSetup extends SetupBase with TestSourceBuilders {

    def appBundles: Seq[ExtensionBundle]

    def config: OperationConfig = {
      val base = OperationConfig.default.withBundlesFor(Parser)
      appBundles.foldLeft(base){ (acc, bundle) => acc.withBundles(Seq(bundle)) }
    }

  }

  trait ParserSetup extends SetupBase {

    final val parserBundles = Nil

    val textBlockParser: Parser[LineSource] = TextParsers.textLine.rep.min(1).mkLines.line

  }

  trait BlockParserSetup extends ParserSetup {

    val input =
      """
        |>aaa
        |aaa
        |
        |+bbb
        |bbb
      """.stripMargin

    case class DecoratedBlock (deco: Char, content: Seq[Span], options: Options = NoOpt) extends Block with SpanContainer {
      type Self = DecoratedBlock
      def withContent (newContent: Seq[Span]): DecoratedBlock = copy(content = newContent)
      def withOptions (options: Options): DecoratedBlock = copy(options = options)
    }
    
    def blockFor (deco: Char): BlockParserBuilder = blockFor(deco, deco)

    def blockFor (deco: Char, overrideDeco: Char): BlockParserBuilder =
      BlockParser.withSpans { spanParsers =>
        builders.oneOf(deco) ~> spanParsers.recursiveSpans(textBlockParser).map(DecoratedBlock(overrideDeco, _))
      }

    def doc (blocks: (Char, String)*): Document =
      Document(Root / "doc", RootElement(blocks.map { case (deco, text) => DecoratedBlock(deco, Seq(Text(text))) }),
        config = docConfig)

  }

  "The configuration for block parsers" should {

    "merge the block parsers from a host language with the block parsers from an app extension" in new BlockParserSetup {
      override def blockParsers: Seq[BlockParserBuilder] = Seq(blockFor('>'))

      val bundle = BundleProvider.forMarkupParser(blockParsers = Seq(blockFor('+')))

      MarkupParser.of(Parser).using(bundle).build.parse(input).toOption.get shouldBe doc('>' -> "aaa\naaa", '+' -> "bbb\nbbb")
    }

    "merge the block parsers from two app extensions" in new BlockParserSetup {
      override def blockParsers: Seq[BlockParserBuilder] = Nil

      val bundle1 = BundleProvider.forMarkupParser(blockParsers = Seq(blockFor('+')))
      val bundle2 = BundleProvider.forMarkupParser(blockParsers = Seq(blockFor('>')))

      MarkupParser.of(Parser).using(bundle1, bundle2).build.parse(input).toOption.get shouldBe doc('>' -> "aaa\naaa", '+' -> "bbb\nbbb")
    }

    "let a block parser from an app extension override a block parser from the host language" in new BlockParserSetup {
      override def blockParsers: Seq[BlockParserBuilder] = Seq(blockFor('>'), blockFor('+'))

      val bundle = BundleProvider.forMarkupParser(blockParsers = Seq(blockFor('+', '!')))

      MarkupParser.of(Parser).using(bundle).build.parse(input).toOption.get shouldBe doc('>' -> "aaa\naaa", '!' -> "bbb\nbbb")
    }

    "let a block parser from the host language override a low-precedence parser from an app extension" in new BlockParserSetup {
      override def blockParsers: Seq[BlockParserBuilder] = Seq(blockFor('>'), blockFor('+'))

      val bundle = BundleProvider.forMarkupParser(blockParsers = Seq(
        BlockParser.standalone(literal("+").map(_ => Rule())).withLowPrecedence
      ))

      MarkupParser.of(Parser).using(bundle).build.parse(input).toOption.get shouldBe doc('>' -> "aaa\naaa", '+' -> "bbb\nbbb")
    }

  }

  trait SpanParserSetup extends ParserSetup {

    import TextParsers._

    val input = ">aaa +bbb"

    override def blockParsers: Seq[BlockParserBuilder] = Seq(BlockParser.withSpans { spanParsers =>
      spanParsers.recursiveSpans(textBlockParser).map(Paragraph(_))
    })

    case class DecoratedSpan (deco: Char, text: String) extends Span {
      val options: Options = NoOpt
      type Self = DecoratedSpan
      def withOptions (options: Options): DecoratedSpan = this
    }

    def spanFor (deco: Char): SpanParserBuilder = spanFor(deco, deco)

    def spanFor (deco: Char, overrideDeco: Char): SpanParserBuilder =
      SpanParser.standalone {
        (deco.toString ~> anyNot(' ') <~ opt(" ")).map(DecoratedSpan(overrideDeco, _))
      }

    def doc (spans: (Char, String)*): Document =
      Document(Root / "doc", RootElement(Paragraph(
        spans.map { case (deco, text) => DecoratedSpan(deco, text) }
      )), config = docConfig)
  }

  "The configuration for span parsers" should {

    "merge the span parsers from a host language with the span parsers from an app extension" in new SpanParserSetup {
      override def spanParsers: Seq[SpanParserBuilder] = Seq(spanFor('>'))

      val bundle = BundleProvider.forMarkupParser(spanParsers = Seq(spanFor('+')))

      MarkupParser.of(Parser).using(bundle).build.parse(input).toOption.get shouldBe doc('>' -> "aaa", '+' -> "bbb")
    }

    "merge the span parsers from two app extensions" in new SpanParserSetup {
      override def spanParsers: Seq[SpanParserBuilder] = Nil

      val bundle1 = BundleProvider.forMarkupParser(spanParsers = Seq(spanFor('+')))
      val bundle2 = BundleProvider.forMarkupParser(spanParsers = Seq(spanFor('>')))

      MarkupParser.of(Parser).using(bundle1, bundle2).build.parse(input).toOption.get shouldBe doc('>' -> "aaa", '+' -> "bbb")
    }

    "let a span parser from an app extension override a span parser from the host language" in new SpanParserSetup {
      override def spanParsers: Seq[SpanParserBuilder] = Seq(spanFor('>'), spanFor('+'))

      val bundle = BundleProvider.forMarkupParser(spanParsers = Seq(spanFor('+', '!')))

      MarkupParser.of(Parser).using(bundle).build.parse(input).toOption.get shouldBe doc('>' -> "aaa", '!' -> "bbb")
    }

    "let a span parser from the host language override a low-precedence parser from an app extension" in new SpanParserSetup {
      override def spanParsers: Seq[SpanParserBuilder] = Seq(spanFor('>'), spanFor('+'))

      val bundle = BundleProvider.forMarkupParser(spanParsers = Seq(
        SpanParser.standalone(literal("+").map(Text(_))).withLowPrecedence
      ))

      MarkupParser.of(Parser).using(bundle).build.parse(input).toOption.get shouldBe doc('>' -> "aaa", '+' -> "bbb")
    }

  }

  trait ParserHookSetup extends SetupBase {

    override def blockParsers: Seq[BlockParserBuilder] = Seq(BlockParser.standalone {
      TextParsers.textLine.map(Paragraph(_))
    })

    def preProcess (append: String): DocumentInput => DocumentInput = { input =>
      val raw = input.source.input
      input.copy(source = SourceCursor(raw + append, input.path))
    }

    def processDoc (append: String): UnresolvedDocument => UnresolvedDocument = { unresolved => unresolved.copy(
       document =
         unresolved.document.copy(content = unresolved.document.content.copy(content = unresolved.document.content.content map {
          case Paragraph(Seq(Text(text, _)), _) => Paragraph(text + append)
        })))
      }

    def processBlocks (append: String): Seq[Block] => Seq[Block] = { blocks =>
      blocks map {
        case Paragraph(Seq(Text(text, _)), _) => Paragraph(text + append)
      }
    }

    def doc (text: String): Document = Document(Root / "doc", RootElement(text), config = docConfig)

  }

  "The configuration for the preProcessInput hook" should {

    "apply the hook from a parser extension before the hook in an app extension" in new ParserHookSetup {
      val parserBundles = Seq(BundleProvider.forParserHooks(preProcessInput = preProcess("!")))
      val appBundle = BundleProvider.forParserHooks(preProcessInput = preProcess("?"))

      MarkupParser.of(Parser).using(appBundle).build.parse("foo").toOption.get shouldBe doc("foo!?")
    }

    "apply the hook from an app extension before the hook in a subsequently installed app extension" in new ParserHookSetup {
      val parserBundles = Nil
      val appBundle1 = BundleProvider.forParserHooks(preProcessInput = preProcess("!"))
      val appBundle2 = BundleProvider.forParserHooks(preProcessInput = preProcess("?"))

      MarkupParser.of(Parser).using(appBundle1, appBundle2).build.parse("foo").toOption.get shouldBe doc("foo!?")
    }

    "provide the identity function when no hook is defined" in new ParserHookSetup {
      val parserBundles = Nil

      MarkupParser.of(Parser).build.parse("foo").toOption.get shouldBe doc("foo")
    }

  }

  "The configuration for the postProcessBlocks hook" should {

    "apply the hook from a parser extension before the hook in an app extension" in new ParserHookSetup {
      val parserBundles = Seq(BundleProvider.forParserHooks(postProcessDocument = processDoc("!")))
      val appBundle = BundleProvider.forParserHooks(postProcessDocument = processDoc("?"))

      MarkupParser.of(Parser).using(appBundle).build.parse("foo").toOption.get shouldBe doc("foo!?")
    }

    "apply the hook from an app extension before the hook in a subsequently installed app extension" in new ParserHookSetup {
      val parserBundles = Nil
      val appBundle1 = BundleProvider.forParserHooks(postProcessDocument = processDoc("!"))
      val appBundle2 = BundleProvider.forParserHooks(postProcessDocument = processDoc("?"))

      MarkupParser.of(Parser).using(appBundle1, appBundle2).build.parse("foo").toOption.get shouldBe doc("foo!?")
    }

    "provide the identity function when no hook is defined" in new ParserHookSetup {
      val parserBundles = Nil

      MarkupParser.of(Parser).build.parse("foo").toOption.get shouldBe doc("foo")
    }

  }

  "The configuration for the postProcessDocument hook" should {

    "apply the hook from a parser extension before the hook in an app extension" in new ParserHookSetup {
      val parserBundles = Seq(BundleProvider.forParserHooks(postProcessBlocks = processBlocks("!")))
      val appBundle = BundleProvider.forParserHooks(postProcessBlocks = processBlocks("?"))

      MarkupParser.of(Parser).using(appBundle).build.parse("foo").toOption.get shouldBe doc("foo!?")
    }

    "apply the hook from an app extension before the hook in a subsequently installed app extension" in new ParserHookSetup {
      val parserBundles = Nil
      val appBundle1 = BundleProvider.forParserHooks(postProcessBlocks = processBlocks("!"))
      val appBundle2 = BundleProvider.forParserHooks(postProcessBlocks = processBlocks("?"))

      MarkupParser.of(Parser).using(appBundle1, appBundle2).build.parse("foo").toOption.get shouldBe doc("foo!?")
    }

    "provide the identity function when no hook is defined" in new ParserHookSetup {
      val parserBundles = Nil

      MarkupParser.of(Parser).build.parse("foo").toOption.get shouldBe doc("foo")
    }

  }

  "The configuration for the config provider" should {

    object BetweenBraces extends ConfigProvider {
      def configHeader: Parser[ConfigParser] = ConfigHeaderParser.betweenLines("{{", "}}")
      def configDocument (input: String): ConfigParser = ConfigParser.empty
    }
    object BetweenAngles extends ConfigProvider {
      def configHeader: Parser[ConfigParser] = ConfigHeaderParser.betweenLines("<<", ">>")
      def configDocument (input: String): ConfigParser = ConfigParser.empty
    }

    def parseWith(opConfig: OperationConfig, input: String): Either[ConfigError, Config] = opConfig
      .configProvider
      .configHeader
      .parse(input) match {
        case Success(builderRoot, _) =>
          builderRoot.resolve(Origin.root, Config.empty, Map.empty)
        case f: Failure => Left(ConfigParserError(f))
      }
    
    val expectedConfig = ConfigBuilder.empty.withValue("foo",7).build

    "should let configuration providers from app bundles override providers from parsers" in new BundleSetup {
      val parserBundles = Seq(BundleProvider.forConfigProvider(BetweenBraces))
      val appBundles = Seq(BundleProvider.forConfigProvider(BetweenAngles))

      parseWith(config, "{{\nfoo: 7\n}}").toOption shouldBe None
      parseWith(config, "<<\nfoo: 7\n>>").toOption shouldBe Some(expectedConfig)
    }

    "let an app config override a config provider in a previously installed app config" in new BundleSetup {
      val parserBundles = Nil
      val appBundles = Seq(
        BundleProvider.forConfigProvider(BetweenBraces),
        BundleProvider.forConfigProvider(BetweenAngles),
      )

      parseWith(config, "{{\nfoo: 7\n}}").toOption shouldBe None
      parseWith(config, "<<\nfoo: 7\n>>").toOption shouldBe Some(expectedConfig)
    }

    "use the default fallback parser in case no provider is installed" in new BundleSetup {
      val parserBundles = Nil
      val appBundles = Nil

      parseWith(config, "{%\nfoo: 7\n%}").toOption shouldBe Some(expectedConfig)
    }

    "fail when it does not recognize the header separator" in new BundleSetup {
      val parserBundles = Nil
      val appBundles = Nil
      val input = "[[\nfoo: 7\n]]"

      parseWith(config, input) match {
        case Left(ConfigParserError(f)) => f.toString shouldBe """[1.1] failure: `{%' expected but `[[` found
                                                                 |
                                                                 |[[
                                                                 |^""".stripMargin
        case other => fail(s"Unexpected result: $other")
      }
    }

  }

  "The configuration for the template parser" should {

    "let an app config override a parser in the extension config" in new BundleSetup {
      val parserBundles = Seq(BundleProvider.forTemplateParser(Parsers.success(TemplateRoot("foo"))))
      val appBundles = Seq(BundleProvider.forTemplateParser(Parsers.success(TemplateRoot("bar"))))

      val templateParser = config.templateParser
      templateParser should not be empty
      templateParser.get.parse("anything").toOption shouldBe Some(TemplateRoot("bar"))
    }

    "let an app config override a parser in a previously installed app config" in new BundleSetup {
      val parserBundles = Nil
      val appBundles = Seq(
        BundleProvider.forTemplateParser(Parsers.success(TemplateRoot("foo"))),
        BundleProvider.forTemplateParser(Parsers.success(TemplateRoot("bar")))
      )

      val templateParser = config.templateParser
      templateParser should not be empty
      templateParser.get.parse("anything").toOption shouldBe Some(TemplateRoot("bar"))
    }

    "use the default parser when there is no parser installed" in new BundleSetup {
      val parserBundles = Nil
      val appBundles = Nil

      val input = "${cursor.currentDocument.content}"
      val contextRef = TemplateContextReference(CursorKeys.documentContent, required = true, generatedSource(input))
      val templateParser = config.templateParser
      templateParser should not be empty
      templateParser.get.parse(input).toOption shouldBe Some(TemplateRoot(contextRef))
    }

    "return None in strict mode when there is no parser installed" in new BundleSetup {
      val parserBundles = Nil
      val appBundles = Nil

      config.forStrictMode.templateParser shouldBe empty
    }

  }

  "The configuration for the style sheet parser" should {

    def style (value: String): Set[StyleDeclaration] = Set(
      StyleDeclaration(StylePredicate.Id("id"), "foo" -> value)
    )

    "let an app config override a parser in the extension config" in new BundleSetup {
      val parserBundles = Seq(BundleProvider.forStyleSheetParser(Parsers.success(style("red"))))
      val appBundles = Seq(BundleProvider.forStyleSheetParser(Parsers.success(style("blue"))))
      config.styleSheetParser.parse("anything").toOption shouldBe Some(style("blue"))
    }

    "let an app config override a parser in a previously installed app config" in new BundleSetup {
      val parserBundles = Nil
      val appBundles = Seq(
        BundleProvider.forStyleSheetParser(Parsers.success(style("red"))),
        BundleProvider.forStyleSheetParser(Parsers.success(style("blue")))
      )
      config.styleSheetParser.parse("anything").toOption shouldBe Some(style("blue"))
    }

    "use the default fallback parser in case all other parsers fail" in new BundleSetup {
      val parserBundles = Nil
      val appBundles = Nil
      config.styleSheetParser shouldBe CSSParsers.styleDeclarationSet
      config.styleSheetParser.parse("#id { foo: blue; }").toOption shouldBe Some(style("blue"))
    }

  }


}
