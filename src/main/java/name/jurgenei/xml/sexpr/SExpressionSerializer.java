package name.jurgenei.xml.sexpr;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SAX content handler that writes bracket-based S-expression representation.
 */
public final class SExpressionSerializer implements ContentHandler, LexicalHandler {
    private final Writer writer;
    private final Deque<NodeFrame> stack = new ArrayDeque<>();
    private final List<NamespaceDecl> pendingNamespaceDeclarations = new ArrayList<>();
    private final List<DocNode> documentNodes = new ArrayList<>();
    private final OutputFormat format;
    private final SyntaxMode syntaxMode;

    /**
     * Rendering mode for serialized S-expression output.
     */
    public enum OutputFormat {
        /** Compact single-line output with minimal whitespace. */
        COMPACT,
        /** Indented multi-line output for readability. */
        BEAUTIFIED
    }

    /**
     * Syntax compatibility mode.
     */
    public enum SyntaxMode {
        /** Keep legacy syntax: comments as (# ...), PI as key="value" pairs, bracket blocks for attrs/namespaces. */
        LEGACY,
        /** Emit canonical syntax: comments as (! ...), PI map form, {} associative blocks, explicit document wrapper. */
        CANONICAL
    }

    /**
     * Creates serializer using {@link OutputFormat#COMPACT} mode.
     *
     * @param writer destination writer
     */
    public SExpressionSerializer(Writer writer) {
        this(writer, OutputFormat.COMPACT, SyntaxMode.CANONICAL);
    }

    /**
     * Creates serializer with explicit output format.
     *
     * @param writer destination writer
     * @param format requested rendering mode; defaults to compact when {@code null}
     */
    public SExpressionSerializer(Writer writer, OutputFormat format) {
        this(writer, format, SyntaxMode.CANONICAL);
    }

    /**
     * Creates serializer with explicit output and syntax mode.
     *
     * @param writer destination writer
     * @param format rendering mode
     * @param syntaxMode syntax compatibility mode
     */
    public SExpressionSerializer(Writer writer, OutputFormat format, SyntaxMode syntaxMode) {
        this.writer = writer;
        this.format = format == null ? OutputFormat.COMPACT : format;
        this.syntaxMode = syntaxMode == null ? SyntaxMode.CANONICAL : syntaxMode;
    }

    @Override
    public void setDocumentLocator(Locator locator) {
    }

    @Override
    public void startDocument() {
        documentNodes.clear();
        stack.clear();
        pendingNamespaceDeclarations.clear();
    }

    @Override
    public void endDocument() throws SAXException {
        try {
            if (syntaxMode == SyntaxMode.CANONICAL) {
                writer.write(renderCanonicalDocument());
            } else {
                for (int i = 0; i < documentNodes.size(); i++) {
                    writer.write(renderDocumentNode(documentNodes.get(i), 0));
                    if (i + 1 < documentNodes.size()) {
                        writer.write(System.lineSeparator());
                    }
                }
            }
            writer.write(System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            throw new SAXException("Failed to flush S-expression output", e);
        }
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) {
        pendingNamespaceDeclarations.add(new NamespaceDecl(prefix == null ? "" : prefix, uri == null ? "" : uri));
    }

    @Override
    public void endPrefixMapping(String prefix) {
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
        NodeKind kind = detectNodeKind(uri, localName, qName);
        NodeFrame frame = new NodeFrame(kind);

        if (kind == NodeKind.ELEMENT) {
            String name = (qName != null && !qName.isBlank()) ? qName : localName;
            frame.name = name;
            frame.namespaceDeclarations.addAll(pendingNamespaceDeclarations);
            for (int i = 0; i < atts.getLength(); i++) {
                String attrName = atts.getQName(i);
                if (attrName == null || attrName.isBlank()) {
                    attrName = atts.getLocalName(i);
                }
                frame.attributes.put(attrName, atts.getValue(i));
            }
        } else if (kind == NodeKind.ENTRY) {
            frame.entryKey = atts.getValue("", "key");
            if (frame.entryKey == null || frame.entryKey.isBlank()) {
                frame.entryKey = atts.getValue("key");
            }
        } else if (kind == NodeKind.LITERAL) {
            frame.literalValue = firstAttribute(atts, "value");
            frame.literalQuoted = Boolean.parseBoolean(firstAttribute(atts, "quoted"));
        } else if (kind == NodeKind.TYPED_ATOMIC) {
            frame.atomicType = firstAttribute(atts, "type");
            frame.literalValue = firstAttribute(atts, "value");
            frame.literalQuoted = Boolean.parseBoolean(firstAttribute(atts, "quoted"));
        } else if (kind == NodeKind.XML_DECLARATION) {
            frame.xmlVersion = firstAttribute(atts, "version");
            frame.xmlEncoding = firstAttribute(atts, "encoding");
            frame.xmlStandalone = firstAttribute(atts, "standalone");
        }

        // Internal xdm:* helper nodes carry synthetic namespace events.
        stack.push(frame);
        pendingNamespaceDeclarations.clear();
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        NodeFrame frame = stack.pop();
        if (stack.isEmpty()) {
            documentNodes.add(DocNode.node(frame));
            return;
        }
        stack.peek().children.add(Child.node(frame));
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (length == 0 || stack.isEmpty()) {
            return;
        }
        String text = new String(ch, start, length);
        if (text.isBlank()) {
            return;
        }
        stack.peek().children.add(Child.text(text));
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) {
    }

    @Override
    public void processingInstruction(String target, String data) {
        PiNode piNode = new PiNode(target, parsePiTokens(data));
        if (stack.isEmpty()) {
            documentNodes.add(DocNode.pi(piNode));
            return;
        }
        stack.peek().children.add(Child.pi(piNode));
    }

    @Override
    public void skippedEntity(String name) {
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
        String text = new String(ch, start, length);
        if (stack.isEmpty()) {
            documentNodes.add(DocNode.comment(text));
            return;
        }
        stack.peek().children.add(Child.comment(text));
    }

    private String renderDocumentNode(DocNode node, int depth) {
        if (node.node != null) {
            return renderNode(node.node, depth);
        }
        if (node.comment != null) {
            return renderComment(node.comment);
        }
        return renderPi(node.pi);
    }

    private String renderNode(NodeFrame frame, int depth) {
        return switch (frame.kind) {
            case ELEMENT -> renderElement(frame, depth);
            case MAP -> renderMap(frame, depth);
            case ARRAY -> renderArray(frame, depth);
            case ITEM -> renderItem(frame, depth);
            case ENTRY -> renderEntry(frame, depth);
            case LITERAL -> renderLiteral(frame.literalValue, frame.literalQuoted);
            case TYPED_ATOMIC -> renderTypedAtomic(frame);
            case XML_DECLARATION -> renderXmlDeclaration(frame, depth);
        };
    }

    private String renderCanonicalDocument() {
        if (format == OutputFormat.BEAUTIFIED) {
            return renderCanonicalDocumentBeautified();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("(.");
        for (DocNode node : documentNodes) {
            sb.append(' ').append(renderDocumentNode(node, 1));
        }
        sb.append(')');
        return sb.toString();
    }

    private String renderCanonicalDocumentBeautified() {
        StringBuilder sb = new StringBuilder();
        sb.append("(.");
        if (documentNodes.isEmpty()) {
            sb.append(')');
            return sb.toString();
        }
        for (DocNode node : documentNodes) {
            sb.append('\n').append(renderDocumentNode(node, 1));
        }
        sb.append(')');
        return sb.toString();
    }

    private String renderElement(NodeFrame frame, int depth) {
        return format == OutputFormat.BEAUTIFIED
            ? renderElementBeautified(frame, depth)
            : renderElementCompact(frame, depth);
    }

    private String renderElementCompact(NodeFrame frame, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append('(').append(frame.name);
        if (syntaxMode == SyntaxMode.CANONICAL) {
            Map<String, String> associative = buildAssociativeBlock(frame);
            if (!associative.isEmpty()) {
                sb.append(' ').append(renderAssociativeBlockCompact(associative));
            }
        } else {
            if (!frame.attributes.isEmpty()) {
                sb.append(' ').append(renderAttributeBlockCompact(frame.attributes));
            }
            if (!frame.namespaceDeclarations.isEmpty()) {
                sb.append(' ').append(renderNamespaceBlockCompact(frame.namespaceDeclarations));
            }
        }
        for (Child child : frame.children) {
            sb.append(' ').append(renderChildCompact(child, depth + 1));
        }
        sb.append(')');
        return sb.toString();
    }

    private String renderElementBeautified(NodeFrame frame, int depth) {
        StringBuilder sb = new StringBuilder();
        String currentIndent = indent(depth);
        sb.append(currentIndent).append('(').append(frame.name);

        boolean hasChildren = !frame.children.isEmpty();
        boolean hasBlocks;
        if (syntaxMode == SyntaxMode.CANONICAL) {
            hasBlocks = !buildAssociativeBlock(frame).isEmpty();
        } else {
            hasBlocks = !frame.attributes.isEmpty() || !frame.namespaceDeclarations.isEmpty();
        }
        boolean hasStructuredChildren = frame.children.stream().anyMatch(child -> child.node != null || child.comment != null || child.pi != null);

        if (syntaxMode == SyntaxMode.CANONICAL) {
            Map<String, String> associative = buildAssociativeBlock(frame);
            if (!associative.isEmpty()) {
                sb.append('\n').append(renderAssociativeBlockBeautified(associative, depth + 1));
            }
        } else {
            if (!frame.attributes.isEmpty()) {
                sb.append('\n').append(renderAttributeBlockBeautified(frame.attributes, depth + 1));
            }
            if (!frame.namespaceDeclarations.isEmpty()) {
                sb.append('\n').append(renderNamespaceBlockBeautified(frame.namespaceDeclarations, depth + 1));
            }
        }

        if (!hasStructuredChildren && hasChildren) {
            for (Child child : frame.children) {
                sb.append(' ').append(quote(child.text));
            }
            sb.append(')');
            return sb.toString();
        }

        for (Child child : frame.children) {
            sb.append('\n').append(renderChildBeautified(child, depth + 1));
        }

        if (!hasChildren && !hasBlocks) {
            sb.append(')');
            return sb.toString();
        }

        sb.append(')');
        return sb.toString();
    }

    private String renderChildCompact(Child child, int depth) {
        if (child.node != null) {
            return renderNode(child.node, depth);
        }
        if (child.comment != null) {
            return renderComment(child.comment);
        }
        if (child.pi != null) {
            return renderPi(child.pi);
        }
        return quote(child.text);
    }

    private String renderChildBeautified(Child child, int depth) {
        if (child.node != null) {
            return renderNode(child.node, depth);
        }
        String leading = indent(depth);
        if (child.comment != null) {
            return leading + renderComment(child.comment);
        }
        if (child.pi != null) {
            return leading + renderPi(child.pi);
        }
        return leading + quote(child.text);
    }

    private String renderAttributeBlockCompact(Map<String, String> attributes) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!first) {
                sb.append(' ');
            }
            sb.append(entry.getKey()).append(' ').append(quote(entry.getValue()));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private String renderAttributeBlockBeautified(Map<String, String> attributes, int depth) {
        StringBuilder sb = new StringBuilder();
        String startIndent = indent(depth);
        String continuationIndent = startIndent + " ";
        int index = 0;

        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (index == 0) {
                sb.append(startIndent).append('[');
            } else {
                sb.append('\n').append(continuationIndent);
            }
            sb.append(entry.getKey()).append(' ').append(quote(entry.getValue()));
            index++;
        }
        sb.append(']');
        return sb.toString();
    }

    private String renderNamespaceBlockCompact(List<NamespaceDecl> declarations) {
        StringBuilder sb = new StringBuilder("[ns");
        if (declarations.size() == 1 && declarations.get(0).prefix.isEmpty()) {
            sb.append(' ').append(quote(declarations.get(0).uri));
        } else {
            for (NamespaceDecl declaration : declarations) {
                sb.append(' ').append(quote(declaration.prefix)).append(' ').append(quote(declaration.uri));
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private String renderNamespaceBlockBeautified(List<NamespaceDecl> declarations, int depth) {
        String compact = renderNamespaceBlockCompact(declarations);
        return indent(depth) + compact;
    }

    private Map<String, String> buildAssociativeBlock(NodeFrame frame) {
        Map<String, String> values = new LinkedHashMap<>();
        for (NamespaceDecl declaration : frame.namespaceDeclarations) {
            String key = declaration.prefix.isEmpty() ? "xmlns" : "xmlns:" + declaration.prefix;
            values.put(key, declaration.uri);
        }
        values.putAll(frame.attributes);
        return values;
    }

    private String renderAssociativeBlockCompact(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                sb.append(' ');
            }
            sb.append(entry.getKey()).append(' ').append(quote(entry.getValue()));
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    private String renderAssociativeBlockBeautified(Map<String, String> values, int depth) {
        StringBuilder sb = new StringBuilder();
        String startIndent = indent(depth);
        String continuationIndent = startIndent + " ";
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (index == 0) {
                sb.append(startIndent).append('{');
            } else {
                sb.append('\n').append(continuationIndent);
            }
            sb.append(entry.getKey()).append(' ').append(quote(entry.getValue()));
            index++;
        }
        sb.append('}');
        return sb.toString();
    }

    private String renderMap(NodeFrame frame, int depth) {
        if (format == OutputFormat.BEAUTIFIED) {
            StringBuilder sb = new StringBuilder();
            sb.append(indent(depth)).append("(xdm:map");
            sb.append('\n').append(renderMapPayloadBeautified(frame, depth + 1));
            sb.append(')');
            return sb.toString();
        }
        return "(xdm:map " + renderMapPayloadCompact(frame, depth + 1) + ')';
    }

    private String renderMapPayloadCompact(NodeFrame frame, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Child child : frame.children) {
            if (child.node == null || child.node.kind != NodeKind.ENTRY) {
                continue;
            }
            if (!first) {
                sb.append(' ');
            }
            sb.append(child.node.entryKey).append(' ').append(renderEntryValue(child.node, depth));
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    private String renderMapPayloadBeautified(NodeFrame frame, int depth) {
        if (frame.children.isEmpty()) {
            return indent(depth) + "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(indent(depth)).append('{');
        for (Child child : frame.children) {
            if (child.node == null || child.node.kind != NodeKind.ENTRY) {
                continue;
            }
            sb.append('\n')
                .append(indent(depth + 1))
                .append(child.node.entryKey)
                .append(' ')
                .append(renderEntryValue(child.node, depth + 1));
        }
        sb.append('}');
        return sb.toString();
    }

    private String renderArray(NodeFrame frame, int depth) {
        if (format == OutputFormat.BEAUTIFIED) {
            StringBuilder sb = new StringBuilder();
            sb.append(indent(depth)).append("(xdm:array");
            sb.append('\n').append(renderArrayPayloadBeautified(frame, depth + 1));
            sb.append(')');
            return sb.toString();
        }
        return "(xdm:array " + renderArrayPayloadCompact(frame, depth + 1) + ')';
    }

    private String renderArrayPayloadCompact(NodeFrame frame, int depth) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (Child child : frame.children) {
            if (child.node == null || child.node.kind != NodeKind.ITEM) {
                continue;
            }
            if (!first) {
                sb.append(' ');
            }
            sb.append(renderItemValue(child.node, depth));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private String renderArrayPayloadBeautified(NodeFrame frame, int depth) {
        if (frame.children.isEmpty()) {
            return indent(depth) + "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(indent(depth)).append('[');
        for (Child child : frame.children) {
            if (child.node == null || child.node.kind != NodeKind.ITEM) {
                continue;
            }
            sb.append('\n').append(indent(depth + 1)).append(renderItemValue(child.node, depth + 1));
        }
        sb.append(']');
        return sb.toString();
    }

    private String renderEntry(NodeFrame frame, int depth) {
        return frame.entryKey + ' ' + renderEntryValue(frame, depth);
    }

    private String renderEntryValue(NodeFrame entry, int depth) {
        for (Child child : entry.children) {
            if (child.node != null) {
                return renderNode(child.node, depth);
            }
            if (child.text != null) {
                return quote(child.text);
            }
        }
        return quote("");
    }

    private String renderItem(NodeFrame frame, int depth) {
        return renderItemValue(frame, depth);
    }

    private String renderItemValue(NodeFrame item, int depth) {
        for (Child child : item.children) {
            if (child.node != null) {
                return renderNode(child.node, depth);
            }
            if (child.text != null) {
                return quote(child.text);
            }
        }
        return quote("");
    }

    private String renderTypedAtomic(NodeFrame frame) {
        String type = frame.atomicType == null || frame.atomicType.isBlank() ? "xs:string" : frame.atomicType;
        return '(' + type + ' ' + renderLiteral(frame.literalValue, frame.literalQuoted) + ')';
    }

    private String renderLiteral(String value, boolean quoted) {
        String safe = value == null ? "" : value;
        return quoted ? quote(safe) : safe;
    }

    private String renderComment(String value) {
        String marker = syntaxMode == SyntaxMode.CANONICAL ? "!" : "#";
        return "(" + marker + " " + quote(value) + ")";
    }

    private String renderPi(PiNode pi) {
        StringBuilder sb = new StringBuilder();
        sb.append("(?").append(pi.target);
        if (syntaxMode == SyntaxMode.CANONICAL) {
            if (!pi.tokens.isEmpty()) {
                sb.append(" {");
                for (int i = 0; i < pi.tokens.size(); i++) {
                    PiToken token = pi.tokens.get(i);
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(token.key).append(' ').append(quote(token.value));
                }
                sb.append('}');
            }
        } else {
            for (PiToken token : pi.tokens) {
                sb.append(' ')
                    .append(token.key)
                    .append('=')
                    .append(quote(token.value));
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private String renderXmlDeclaration(NodeFrame frame, int depth) {
        StringBuilder sb = new StringBuilder();
        if (format == OutputFormat.BEAUTIFIED) {
            sb.append(indent(depth));
        }
        sb.append('{');
        boolean first = true;
        if (frame.xmlVersion != null) {
            sb.append("version ").append(quote(frame.xmlVersion));
            first = false;
        }
        if (frame.xmlEncoding != null) {
            if (!first) {
                sb.append(' ');
            }
            sb.append("encoding ").append(quote(frame.xmlEncoding));
            first = false;
        }
        if (frame.xmlStandalone != null) {
            if (!first) {
                sb.append(' ');
            }
            sb.append("standalone ").append(quote(frame.xmlStandalone));
        }
        sb.append('}');
        return sb.toString();
    }

    private List<PiToken> parsePiTokens(String data) {
        List<PiToken> tokens = new ArrayList<>();
        if (data == null || data.isBlank()) {
            return tokens;
        }

        Cursor cursor = new Cursor(data);
        while (true) {
            cursor.skipWhitespace();
            if (cursor.isEof()) {
                return tokens;
            }

            String key = cursor.readKey();
            if (key.isEmpty()) {
                return List.of(new PiToken("data", data));
            }
            if (!cursor.consume('=')) {
                return List.of(new PiToken("data", data));
            }
            String value = cursor.readQuoted();
            if (value == null) {
                return List.of(new PiToken("data", data));
            }
            tokens.add(new PiToken(key, value));
        }
    }

    private String indent(int depth) {
        return "  ".repeat(Math.max(0, depth));
    }

    private String quote(String value) {
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return '"' + escaped + '"';
    }

    private NodeKind detectNodeKind(String uri, String localName, String qName) {
        String name = localName;
        if (name == null || name.isBlank()) {
            name = qName;
            int colon = name == null ? -1 : name.indexOf(':');
            if (colon >= 0 && colon + 1 < name.length()) {
                name = name.substring(colon + 1);
            }
        }
        String effectiveUri = uri == null ? "" : uri;
        if (SExpressionParser.INTERNAL_XDM_URI.equals(effectiveUri)) {
            return internalKindByLocal(name);
        }
        if (qName != null && qName.startsWith(SExpressionParser.INTERNAL_XDM_PREFIX + ":")) {
            return internalKindByLocal(name);
        }
        return NodeKind.ELEMENT;
    }

    private NodeKind internalKindByLocal(String localName) {
        if ("map".equals(localName)) {
            return NodeKind.MAP;
        }
        if ("entry".equals(localName)) {
            return NodeKind.ENTRY;
        }
        if ("array".equals(localName)) {
            return NodeKind.ARRAY;
        }
        if ("item".equals(localName)) {
            return NodeKind.ITEM;
        }
        if ("literal".equals(localName)) {
            return NodeKind.LITERAL;
        }
        if ("typed-atomic".equals(localName)) {
            return NodeKind.TYPED_ATOMIC;
        }
        if ("xml-decl".equals(localName)) {
            return NodeKind.XML_DECLARATION;
        }
        return NodeKind.ELEMENT;
    }

    private String firstAttribute(Attributes atts, String key) {
        String byLocal = atts.getValue("", key);
        if (byLocal != null) {
            return byLocal;
        }
        return atts.getValue(key);
    }

    private enum NodeKind {
        ELEMENT,
        MAP,
        ENTRY,
        ARRAY,
        ITEM,
        LITERAL,
        TYPED_ATOMIC,
        XML_DECLARATION
    }

    private static final class NodeFrame {
        private final NodeKind kind;
        private String name;
        private String entryKey;
        private String literalValue;
        private boolean literalQuoted;
        private String atomicType;
        private String xmlVersion;
        private String xmlEncoding;
        private String xmlStandalone;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<NamespaceDecl> namespaceDeclarations = new ArrayList<>();
        private final List<Child> children = new ArrayList<>();

        private NodeFrame(NodeKind kind) {
            this.kind = kind;
        }
    }

    private record NamespaceDecl(String prefix, String uri) {
    }

    private record PiToken(String key, String value) {
    }

    private record PiNode(String target, List<PiToken> tokens) {
    }

    private static final class Child {
        private final NodeFrame node;
        private final String text;
        private final String comment;
        private final PiNode pi;

        private Child(NodeFrame node, String text, String comment, PiNode pi) {
            this.node = node;
            this.text = text;
            this.comment = comment;
            this.pi = pi;
        }

        private static Child node(NodeFrame node) {
            return new Child(node, null, null, null);
        }

        private static Child text(String text) {
            return new Child(null, text, null, null);
        }

        private static Child comment(String comment) {
            return new Child(null, null, comment, null);
        }

        private static Child pi(PiNode pi) {
            return new Child(null, null, null, pi);
        }
    }

    private static final class DocNode {
        private final NodeFrame node;
        private final String comment;
        private final PiNode pi;

        private DocNode(NodeFrame node, String comment, PiNode pi) {
            this.node = node;
            this.comment = comment;
            this.pi = pi;
        }

        private static DocNode node(NodeFrame node) {
            return new DocNode(node, null, null);
        }

        private static DocNode comment(String comment) {
            return new DocNode(null, comment, null);
        }

        private static DocNode pi(PiNode pi) {
            return new DocNode(null, null, pi);
        }
    }

    private static final class Cursor {
        private final String source;
        private int index;

        private Cursor(String source) {
            this.source = source;
        }

        private boolean isEof() {
            return index >= source.length();
        }

        private void skipWhitespace() {
            while (!isEof() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        private String readKey() {
            int start = index;
            while (!isEof()) {
                char ch = source.charAt(index);
                if (Character.isWhitespace(ch) || ch == '=') {
                    break;
                }
                index++;
            }
            return source.substring(start, index);
        }

        private boolean consume(char expected) {
            if (isEof() || source.charAt(index) != expected) {
                return false;
            }
            index++;
            return true;
        }

        private String readQuoted() {
            if (isEof() || source.charAt(index) != '"') {
                return null;
            }
            index++;
            StringBuilder sb = new StringBuilder();
            while (!isEof()) {
                char ch = source.charAt(index++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    if (isEof()) {
                        return null;
                    }
                    char escaped = source.charAt(index++);
                    switch (escaped) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(escaped);
                    }
                    continue;
                }
                sb.append(ch);
            }
            return null;
        }
    }
}


