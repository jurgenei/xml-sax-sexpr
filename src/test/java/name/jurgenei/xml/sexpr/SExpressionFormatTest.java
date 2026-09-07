package name.jurgenei.xml.sexpr;

import org.junit.Assert;
import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class SExpressionFormatTest {

    @Test
    public void parsesCanonicalSyntaxWithNamespacesCommentsAndPi() throws Exception {
        String input = """
            (m:math
              { xmlns:m "http://www.w3.org/1998/Math/MathML" id "b1" version "1.0" }
              (! "this is a comment")
              (?xml-stylesheet { type "text/xsl" href "style.xsl" })
              (m:mfrac
                (m:mi "a")
                (m:mi "b")))
            """;

        RecordingHandler handler = new RecordingHandler();
        new SExpressionParser().parse(new StringReader(input), handler, handler);

        Assert.assertTrue(handler.events.contains("spm:m=http://www.w3.org/1998/Math/MathML"));
        Assert.assertTrue(handler.events.contains("start:m:math:id=b1,version=1.0"));
        Assert.assertTrue(handler.events.contains("comment:this is a comment"));
        Assert.assertTrue(handler.events.contains("pi:xml-stylesheet:type=\"text/xsl\" href=\"style.xsl\""));
        Assert.assertTrue(handler.events.contains("chars:a"));
        Assert.assertTrue(handler.events.contains("chars:b"));
    }

    @Test
    public void serializesCanonicalSyntaxWithStablePiTokens() throws Exception {
        String input = """
            (book
              { id "b1" version "1.0" }
              (?xml-stylesheet { type "text/xsl" href "style.xsl" })
              (title "XML"))
            """;

        StringWriter writer = new StringWriter();
        SExpressionSerializer serializer = new SExpressionSerializer(writer, SExpressionSerializer.OutputFormat.COMPACT);
        new SExpressionParser().parse(new StringReader(input), serializer, serializer);

        String output = writer.toString();
        Assert.assertTrue(output.contains("{id \"b1\" version \"1.0\"}"));
        Assert.assertTrue(output.contains("(?xml-stylesheet {type \"text/xsl\" href \"style.xsl\"})"));
    }

    @Test
    public void rejectsLegacyAtAttributeSyntax() {
        String input = "(book (@id \"b1\") (title \"XML\"))";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertNotNull(error.getMessage());
    }

    @Test
    public void parsesSxdmDocumentNodeMapArrayAndTypedAtomic() throws Exception {
        String input = """
            (.
              (! "lead comment")
              (book
                { xmlns:m "urn:math" id "b1" }
                (xdm:map { meta (xdm:map { name "John" age 42 }) })
                (xdm:array [ "A" 7 (xs:boolean true) ])
                (xs:date "2026-09-06")
                (?xml-stylesheet { href "main.xsl" type "text/xsl" })
                (m:title "XML")))
            """;

        RecordingHandler handler = new RecordingHandler();
        new SExpressionParser().parse(new StringReader(input), handler, handler);

        Assert.assertTrue(handler.events.contains("comment:lead comment"));
        Assert.assertTrue(handler.events.contains("spm:m=urn:math"));
        Assert.assertTrue(handler.events.contains("start:book:id=b1"));
        Assert.assertTrue(handler.events.stream().anyMatch(event -> event.startsWith("start:xdm:map")));
        Assert.assertTrue(handler.events.stream().anyMatch(event -> event.startsWith("start:xdm:array")));
        Assert.assertTrue(handler.events.stream().anyMatch(event -> event.startsWith("start:xdm:typed-atomic:type=xs:date")));
    }

    @Test
    public void roundTripsSxdmConstructsInCompactMode() throws Exception {
        String input = """
            (.
              (book
                { id "b1" }
                (xdm:map { data (xdm:map { name "John" age 42 }) })
                (xdm:array [ "A" 7 (xs:boolean true) ])
                (xs:date "2026-09-06")))
            """;

        StringWriter writer = new StringWriter();
        SExpressionSerializer serializer = new SExpressionSerializer(writer, SExpressionSerializer.OutputFormat.COMPACT);
        new SExpressionParser().parse(new StringReader(input), serializer, serializer);

        String output = writer.toString();
        Assert.assertTrue(output.contains("(xdm:map {data (xdm:map {name \"John\" age 42})})"));
        Assert.assertTrue(output.contains("(xdm:array [\"A\" 7 (xs:boolean true)])"));
        Assert.assertTrue(output.contains("(xs:date \"2026-09-06\")"));
    }

    @Test
    public void rejectsBareSequenceBlockAsNode() {
        String input = "(book [\"A\" \"B\"])";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertTrue(error.getMessage().contains("xdm:array"));
    }

    @Test
    public void serializesCanonicalCommentAndPiMapSyntaxWhenEnabled() throws Exception {
        String input = """
            (.
              (! "lead comment")
              (?xml-stylesheet { href "main.xsl" type "text/xsl" })
              (book { id "b1" } (title "XML")))
            """;

        StringWriter writer = new StringWriter();
        SExpressionSerializer serializer = new SExpressionSerializer(
            writer,
            SExpressionSerializer.OutputFormat.COMPACT,
            SExpressionSerializer.SyntaxMode.CANONICAL
        );
        new SExpressionParser().parse(new StringReader(input), serializer, serializer);

        String output = writer.toString();
        Assert.assertTrue(output.contains("(! \"lead comment\")"));
        Assert.assertTrue(output.contains("(?xml-stylesheet {href \"main.xsl\" type \"text/xsl\"})"));
        Assert.assertTrue(output.startsWith("(. "));
    }

    @Test
    public void parsesAndRoundTripsDocumentXmlDeclarationMap() throws Exception {
        String input = """
            (.
              { version "1.0" encoding "UTF-8" }
              (book { id "b1" } (title "XML")))
            """;

        RecordingHandler handler = new RecordingHandler();
        new SExpressionParser().parse(new StringReader(input), handler, handler);
        Assert.assertTrue(handler.events.stream().anyMatch(event -> event.startsWith("start:xdm:xml-decl:version=1.0,encoding=UTF-8")));

        StringWriter writer = new StringWriter();
        SExpressionSerializer serializer = new SExpressionSerializer(
            writer,
            SExpressionSerializer.OutputFormat.COMPACT,
            SExpressionSerializer.SyntaxMode.CANONICAL
        );
        new SExpressionParser().parse(new StringReader(input), serializer, serializer);

        String output = writer.toString();
        Assert.assertTrue(output.contains("{version \"1.0\" encoding \"UTF-8\"}"));
    }

    @Test
    public void rejectsMapWithMissingValue() {
        String input = "(book { id })";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertTrue(error.getMessage().contains("Unexpected end of input") || error.getMessage().contains("Value token missing"));
    }

    @Test
    public void rejectsArrayWithMissingClosingBracket() {
        String input = "(book (xdm:array [\"A\" \"B\"))";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertTrue(error.getMessage().contains("Unexpected end of input while parsing array")
            || error.getMessage().contains("Expected ')'")
            || error.getMessage().contains("Value token missing"));
    }

    @Test
    public void rejectsTypedAtomicWithoutValue() {
        String input = "(book (xs:boolean))";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertTrue(error.getMessage().contains("Typed atomic value missing") || error.getMessage().contains("Unexpected end of input"));
    }

    @Test
    public void rejectsPiMapWithNonAtomicValue() {
        String input = "(?xml-stylesheet { href { nested \"x\" } })";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertTrue(error.getMessage().contains("must be atomic"));
    }

    @Test
    public void rejectsTopLevelAssociativeBlockWithoutNodeHead() {
        String input = "{ key \"value\" }";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertTrue(error.getMessage().contains("Associative block must be attached to a node head"));
    }

    @Test
    public void rejectsXdmMapWithoutAssociativePayload() {
        String input = "(xdm:map \"oops\")";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertTrue(error.getMessage().contains("xdm:map requires associative payload block"));
    }

    @Test
    public void rejectsAssociativeBlockAfterStructuredChildren() {
        String input = "(book (title \"x\") { id \"b1\" })";
        IOException error = Assert.assertThrows(IOException.class,
            () -> new SExpressionParser().parse(new StringReader(input), new DefaultHandler()));
        Assert.assertTrue(error.getMessage().contains("allowed only before child nodes"));
    }

    private static final class RecordingHandler extends DefaultHandler implements LexicalHandler {
        private final List<String> events = new ArrayList<>();

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            events.add("spm:" + prefix + "=" + uri);
        }

        @Override
        public void endPrefixMapping(String prefix) {
            events.add("epm:" + prefix);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            StringBuilder sb = new StringBuilder();
            sb.append("start:").append(qName);
            if (attributes.getLength() > 0) {
                sb.append(":");
                for (int i = 0; i < attributes.getLength(); i++) {
                    if (i > 0) {
                        sb.append(",");
                    }
                    sb.append(attributes.getQName(i)).append("=").append(attributes.getValue(i));
                }
            }
            events.add(sb.toString());
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            events.add("end:" + qName);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            events.add("chars:" + new String(ch, start, length));
        }

        @Override
        public void processingInstruction(String target, String data) {
            events.add("pi:" + target + ":" + data);
        }

        @Override
        public void startDTD(String name, String publicId, String systemId) {
        }

        @Override
        public void endDTD() {
        }

        @Override
        public void startEntity(String name) {
        }

        @Override
        public void endEntity(String name) {
        }

        @Override
        public void startCDATA() {
        }

        @Override
        public void endCDATA() {
        }

        @Override
        public void comment(char[] ch, int start, int length) {
            events.add("comment:" + new String(ch, start, length));
        }
    }
}



