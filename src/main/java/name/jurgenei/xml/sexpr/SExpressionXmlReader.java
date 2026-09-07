package name.jurgenei.xml.sexpr;

import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * XMLReader facade over {@link SExpressionParser}.
 */
public final class SExpressionXmlReader implements XMLReader {
    private static final String FEATURE_NAMESPACES = "http://xml.org/sax/features/namespaces";
    private static final String FEATURE_NAMESPACE_PREFIXES = "http://xml.org/sax/features/namespace-prefixes";
    private static final String FEATURE_VALIDATION = "http://xml.org/sax/features/validation";
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";
    private static final String PROPERTY_LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler";
    private static final String PROPERTY_DECLARATION_HANDLER = "http://xml.org/sax/properties/declaration-handler";

    private final SExpressionParser parser = new SExpressionParser();

    private ContentHandler contentHandler = new DefaultHandler();
    private DTDHandler dtdHandler;
    private EntityResolver entityResolver;
    private ErrorHandler errorHandler;
    private Object lexicalHandler;
    private Object declarationHandler;

    /**
     * Creates XMLReader facade for S-expression input.
     */
    public SExpressionXmlReader() {
    }

    @Override
    public boolean getFeature(String name) throws SAXNotRecognizedException {
        if (FEATURE_NAMESPACES.equals(name)) {
            return true;
        }
        if (FEATURE_NAMESPACE_PREFIXES.equals(name)) {
            return false;
        }
        if (FEATURE_VALIDATION.equals(name)
            || FEATURE_EXTERNAL_GENERAL_ENTITIES.equals(name)
            || FEATURE_EXTERNAL_PARAMETER_ENTITIES.equals(name)) {
            return false;
        }
        throw new SAXNotRecognizedException(name);
    }

    @Override
    public void setFeature(String name, boolean value) throws SAXNotRecognizedException, SAXNotSupportedException {
        if (FEATURE_NAMESPACES.equals(name) && value) {
            return;
        }
        if (FEATURE_NAMESPACE_PREFIXES.equals(name) && !value) {
            return;
        }
        if ((FEATURE_VALIDATION.equals(name)
            || FEATURE_EXTERNAL_GENERAL_ENTITIES.equals(name)
            || FEATURE_EXTERNAL_PARAMETER_ENTITIES.equals(name))
            && !value) {
            return;
        }
        if (FEATURE_NAMESPACES.equals(name) || FEATURE_NAMESPACE_PREFIXES.equals(name)) {
            throw new SAXNotSupportedException("Unsupported value for feature: " + name);
        }
        if (FEATURE_VALIDATION.equals(name)
            || FEATURE_EXTERNAL_GENERAL_ENTITIES.equals(name)
            || FEATURE_EXTERNAL_PARAMETER_ENTITIES.equals(name)) {
            throw new SAXNotSupportedException("Unsupported value for feature: " + name);
        }
        throw new SAXNotRecognizedException(name);
    }

    @Override
    public Object getProperty(String name) throws SAXNotRecognizedException {
        if (PROPERTY_LEXICAL_HANDLER.equals(name)) {
            return lexicalHandler;
        }
        if (PROPERTY_DECLARATION_HANDLER.equals(name)) {
            return declarationHandler;
        }
        throw new SAXNotRecognizedException(name);
    }

    @Override
    public void setProperty(String name, Object value) throws SAXNotRecognizedException {
        if (PROPERTY_LEXICAL_HANDLER.equals(name)) {
            lexicalHandler = value;
            return;
        }
        if (PROPERTY_DECLARATION_HANDLER.equals(name)) {
            declarationHandler = value;
            return;
        }
        throw new SAXNotRecognizedException(name);
    }

    @Override
    public void setEntityResolver(EntityResolver resolver) {
        this.entityResolver = resolver;
    }

    @Override
    public EntityResolver getEntityResolver() {
        return entityResolver;
    }

    @Override
    public void setDTDHandler(DTDHandler handler) {
        this.dtdHandler = handler;
    }

    @Override
    public DTDHandler getDTDHandler() {
        return dtdHandler;
    }

    @Override
    public void setContentHandler(ContentHandler handler) {
        this.contentHandler = handler == null ? new DefaultHandler() : handler;
    }

    @Override
    public ContentHandler getContentHandler() {
        return contentHandler;
    }

    @Override
    public void setErrorHandler(ErrorHandler handler) {
        this.errorHandler = handler;
    }

    @Override
    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    @Override
    public void parse(InputSource input) throws IOException, SAXException {
        try (Reader reader = openReader(input)) {
            LexicalHandler lexical = lexicalHandler instanceof LexicalHandler value ? value : null;
            parser.parse(reader, contentHandler, lexical);
        }
    }

    @Override
    public void parse(String systemId) throws IOException, SAXException {
        parse(new InputSource(systemId));
    }

    private Reader openReader(InputSource input) throws IOException {
        if (input.getCharacterStream() != null) {
            return input.getCharacterStream();
        }
        if (input.getByteStream() != null) {
            return new InputStreamReader(input.getByteStream(), StandardCharsets.UTF_8);
        }

        String systemId = input.getSystemId();
        if (systemId == null || systemId.isBlank()) {
            throw new IOException("S-expression input requires character stream, byte stream, or systemId");
        }
        URI uri = URI.create(systemId);
        InputStream stream = uri.toURL().openStream();
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }
}


