package name.jurgenei.xml.sexpr;

import org.junit.Assert;
import org.junit.Test;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class SExpressionXmlReaderTest {

    @Test
    public void reportsSupportedAndUnsupportedFeatures() throws Exception {
        SExpressionXmlReader reader = new SExpressionXmlReader();

        Assert.assertTrue(reader.getFeature("http://xml.org/sax/features/namespaces"));
        Assert.assertFalse(reader.getFeature("http://xml.org/sax/features/namespace-prefixes"));
        Assert.assertFalse(reader.getFeature("http://xml.org/sax/features/validation"));

        Assert.assertThrows(SAXNotRecognizedException.class,
            () -> reader.getFeature("urn:test:unknown-feature"));
    }

    @Test
    public void acceptsAndRejectsFeatureValues() throws Exception {
        SExpressionXmlReader reader = new SExpressionXmlReader();

        reader.setFeature("http://xml.org/sax/features/namespaces", true);
        reader.setFeature("http://xml.org/sax/features/namespace-prefixes", false);
        reader.setFeature("http://xml.org/sax/features/validation", false);

        Assert.assertThrows(SAXNotSupportedException.class,
            () -> reader.setFeature("http://xml.org/sax/features/namespaces", false));
        Assert.assertThrows(SAXNotSupportedException.class,
            () -> reader.setFeature("http://xml.org/sax/features/namespace-prefixes", true));
        Assert.assertThrows(SAXNotSupportedException.class,
            () -> reader.setFeature("http://xml.org/sax/features/validation", true));
    }

    @Test
    public void storesSupportedProperties() throws Exception {
        SExpressionXmlReader reader = new SExpressionXmlReader();
        LexicalHandler lexical = new RecordingHandler();
        Object declaration = new Object();

        reader.setProperty("http://xml.org/sax/properties/lexical-handler", lexical);
        reader.setProperty("http://xml.org/sax/properties/declaration-handler", declaration);

        Assert.assertSame(lexical, reader.getProperty("http://xml.org/sax/properties/lexical-handler"));
        Assert.assertSame(declaration, reader.getProperty("http://xml.org/sax/properties/declaration-handler"));

        Assert.assertThrows(SAXNotRecognizedException.class,
            () -> reader.getProperty("urn:test:unknown-property"));
    }

    @Test
    public void keepsHandlersAndDefaultsContentHandler() {
        SExpressionXmlReader reader = new SExpressionXmlReader();

        EntityResolver entityResolver = (publicId, systemId) -> null;
        DTDHandler dtdHandler = new DefaultHandler();
        ErrorHandler errorHandler = new DefaultHandler();

        reader.setEntityResolver(entityResolver);
        reader.setDTDHandler(dtdHandler);
        reader.setErrorHandler(errorHandler);
        reader.setContentHandler(null);

        Assert.assertSame(entityResolver, reader.getEntityResolver());
        Assert.assertSame(dtdHandler, reader.getDTDHandler());
        Assert.assertSame(errorHandler, reader.getErrorHandler());
        Assert.assertNotNull(reader.getContentHandler());
    }

    @Test
    public void parsesFromCharacterByteAndSystemIdSources() throws Exception {
        String sexpr = "(. (! \"c\") (book { id \"b1\" } (title \"XML\")))";

        SExpressionXmlReader fromChars = new SExpressionXmlReader();
        RecordingHandler charsHandler = new RecordingHandler();
        fromChars.setContentHandler(charsHandler);
        fromChars.setProperty("http://xml.org/sax/properties/lexical-handler", charsHandler);
        fromChars.parse(new InputSource(new StringReader(sexpr)));
        Assert.assertTrue(charsHandler.sawStartBook);
        Assert.assertTrue(charsHandler.sawComment);

        SExpressionXmlReader fromBytes = new SExpressionXmlReader();
        RecordingHandler bytesHandler = new RecordingHandler();
        fromBytes.setContentHandler(bytesHandler);
        fromBytes.parse(new InputSource(new ByteArrayInputStream(sexpr.getBytes(StandardCharsets.UTF_8))));
        Assert.assertTrue(bytesHandler.sawStartBook);

        File temp = File.createTempFile("sexpr-reader", ".sexpr");
        Files.writeString(temp.toPath(), sexpr, StandardCharsets.UTF_8);
        temp.deleteOnExit();

        SExpressionXmlReader fromSystemId = new SExpressionXmlReader();
        RecordingHandler fileHandler = new RecordingHandler();
        fromSystemId.setContentHandler(fileHandler);
        fromSystemId.parse(temp.toURI().toString());
        Assert.assertTrue(fileHandler.sawStartBook);
    }

    @Test
    public void failsWithoutCharacterByteOrSystemId() {
        SExpressionXmlReader reader = new SExpressionXmlReader();
        IOException error = Assert.assertThrows(IOException.class,
            () -> reader.parse(new InputSource()));
        Assert.assertTrue(error.getMessage().contains("requires character stream, byte stream, or systemId"));
    }

    private static final class RecordingHandler extends DefaultHandler implements LexicalHandler {
        private boolean sawStartBook;
        private boolean sawComment;

        @Override
        public void startElement(String uri, String localName, String qName, org.xml.sax.Attributes attributes) {
            if ("book".equals(qName) || "book".equals(localName)) {
                sawStartBook = true;
            }
        }

        @Override
        public void comment(char[] ch, int start, int length) {
            sawComment = true;
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
    }
}

