package name.jurgenei.xml.sexpr;

import org.junit.Assert;
import org.junit.Test;
import org.xml.sax.helpers.AttributesImpl;

import java.io.StringWriter;

public class SExpressionSerializerSaxEventsTest {

    @Test
    public void serializesFromDirectSaxEventsAndCoversInternalKinds() throws Exception {
        StringWriter writer = new StringWriter();
        SExpressionSerializer serializer = new SExpressionSerializer(
            writer,
            SExpressionSerializer.OutputFormat.BEAUTIFIED,
            SExpressionSerializer.SyntaxMode.CANONICAL
        );

        serializer.setDocumentLocator(null);
        serializer.startDocument();
        serializer.ignorableWhitespace(new char[]{' '}, 0, 1);
        serializer.startDTD("d", null, null);
        serializer.endDTD();
        serializer.startEntity("e");
        serializer.endEntity("e");
        serializer.startCDATA();
        serializer.endCDATA();
        serializer.skippedEntity("x");

        // Internal XML declaration node through qName-based kind detection.
        AttributesImpl declAttrs = new AttributesImpl();
        declAttrs.addAttribute("", "version", "version", "CDATA", "1.0");
        declAttrs.addAttribute("", "encoding", "encoding", "CDATA", "UTF-8");
        declAttrs.addAttribute("", "standalone", "standalone", "CDATA", "yes");
        serializer.startElement("", "xml-decl", "xdm:xml-decl", declAttrs);
        serializer.endElement("", "xml-decl", "xdm:xml-decl");

        // Internal typed atomic without type attribute hits default xs:string.
        AttributesImpl typedAttrs = new AttributesImpl();
        typedAttrs.addAttribute("", "value", "value", "CDATA", "v");
        typedAttrs.addAttribute("", "quoted", "quoted", "CDATA", "true");
        serializer.startElement("", "typed-atomic", "xdm:typed-atomic", typedAttrs);
        serializer.endElement("", "typed-atomic", "xdm:typed-atomic");

        // Internal map with one entry/literal.
        serializer.startElement("", "map", "xdm:map", new AttributesImpl());
        AttributesImpl entryAttrs = new AttributesImpl();
        entryAttrs.addAttribute("", "key", "key", "CDATA", "k");
        serializer.startElement("", "entry", "xdm:entry", entryAttrs);
        AttributesImpl literalAttrs = new AttributesImpl();
        literalAttrs.addAttribute("", "value", "value", "CDATA", "42");
        literalAttrs.addAttribute("", "quoted", "quoted", "CDATA", "false");
        serializer.startElement("", "literal", "xdm:literal", literalAttrs);
        serializer.endElement("", "literal", "xdm:literal");
        serializer.endElement("", "entry", "xdm:entry");
        serializer.endElement("", "map", "xdm:map");

        // Internal array with one literal item.
        serializer.startElement("", "array", "xdm:array", new AttributesImpl());
        serializer.startElement("", "item", "xdm:item", new AttributesImpl());
        AttributesImpl arrayLiteralAttrs = new AttributesImpl();
        arrayLiteralAttrs.addAttribute("", "value", "value", "CDATA", "A");
        arrayLiteralAttrs.addAttribute("", "quoted", "quoted", "CDATA", "true");
        serializer.startElement("", "literal", "xdm:literal", arrayLiteralAttrs);
        serializer.endElement("", "literal", "xdm:literal");
        serializer.endElement("", "item", "xdm:item");
        serializer.endElement("", "array", "xdm:array");

        // Regular element with namespace mapping and mixed node kinds.
        serializer.startPrefixMapping("m", "urn:math");
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "id", "id", "CDATA", "b1");
        serializer.startElement("urn:math", "book", "m:book", attrs);
        serializer.characters("".toCharArray(), 0, 0);
        serializer.characters("text".toCharArray(), 0, 4);
        serializer.comment("inside".toCharArray(), 0, 6);
        serializer.processingInstruction("p", "k=\"v\"");
        serializer.endElement("urn:math", "book", "m:book");

        serializer.comment("top".toCharArray(), 0, 3);
        serializer.processingInstruction("pi", "broken-data");

        serializer.endDocument();

        String out = writer.toString();
        Assert.assertTrue(out.contains("{version \"1.0\" encoding \"UTF-8\" standalone \"yes\"}"));
        Assert.assertTrue(out.contains("(xs:string \"v\")"));
        Assert.assertTrue(out.contains("(xdm:map"));
        Assert.assertTrue(out.contains("(xdm:array"));
        Assert.assertTrue(out.contains("(m:book"));
        Assert.assertTrue(out.contains("(! \"inside\")"));
        Assert.assertTrue(out.contains("(?p {k \"v\"})"));
        Assert.assertTrue(out.contains("(?pi {data \"broken-data\"})"));
    }
}

