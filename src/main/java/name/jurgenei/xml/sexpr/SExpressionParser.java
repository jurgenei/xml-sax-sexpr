package name.jurgenei.xml.sexpr;

import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.AttributesImpl;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses bracket-based S-expression format into SAX events.
 */
public final class SExpressionParser {
    static final String INTERNAL_XDM_URI = "urn:name.jurgenei.xml:xdm";
    static final String INTERNAL_XDM_PREFIX = "xdm";
    private static final String XDM_MAP_HEAD = "xdm:map";
    private static final String XDM_ARRAY_HEAD = "xdm:array";
    private static final Set<String> SUPPORTED_TYPED_ATOMICS = Set.of(
        "xs:string",
        "xs:boolean",
        "xs:integer",
        "xs:decimal",
        "xs:double",
        "xs:date",
        "xs:dateTime"
    );
    private static final Set<String> XML_DECLARATION_KEYS = Set.of("version", "encoding", "standalone");

    /**
     * Creates parser for bracket-based S-expression syntax.
     */
    public SExpressionParser() {
    }

    /**
     * Parses S-expression input and emits equivalent SAX events.
     *
     * @param reader character stream containing S-expression document
     * @param handler SAX handler receiving parsed events
     * @throws IOException if input is malformed or cannot be read
     * @throws SAXException if SAX handler fails while consuming events
     */
    public void parse(Reader reader, ContentHandler handler) throws IOException, SAXException {
        LexicalHandler lexical = handler instanceof LexicalHandler value ? value : null;
        parse(reader, handler, lexical);
    }

    /**
     * Parses S-expression input and emits SAX events including optional lexical events.
     *
     * @param reader character stream containing S-expression document
     * @param handler SAX handler receiving parsed events
     * @param lexical optional lexical handler for comments
     * @throws IOException if input is malformed or cannot be read
     * @throws SAXException if SAX handler fails while consuming events
     */
    public void parse(Reader reader, ContentHandler handler, LexicalHandler lexical) throws IOException, SAXException {
        StringBuilder source = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            source.append(buffer, 0, read);
        }

        Cursor cursor = new Cursor(source.toString());
        cursor.skipTrivia();
        Item root = parseItem(cursor);

        cursor.skipTrivia();
        if (!cursor.isEof()) {
            throw new IOException("Unexpected trailing content at position " + cursor.position());
        }

        handler.startDocument();
        emitDocumentRoot(root, handler, lexical, new ArrayDeque<>());
        handler.endDocument();
    }

    private Item parseItem(Cursor cursor) throws IOException {
        cursor.skipTrivia();
        if (cursor.isEof()) {
            throw new IOException("Unexpected end of input at position " + cursor.position());
        }

        char opener = cursor.peek();
        if (opener == '{') {
            throw new IOException("Associative block must be attached to a node head at position " + cursor.position());
        }
        if (opener == '[') {
            throw new IOException("Sequence block must be wrapped in (xdm:array [...]) at position " + cursor.position());
        }
        if (opener == '"') {
            return new TextNode(cursor.readString());
        }

        if (opener != '(') {
            String symbol = cursor.readSymbol();
            if (symbol.isBlank()) {
                throw new IOException("Missing item head at position " + cursor.position());
            }
            return new AtomicNode(symbol, false);
        }

        cursor.expect('(');
        cursor.skipTrivia();
        String head = cursor.readSymbol();
        if (head.isEmpty()) {
            throw new IOException("Missing item head at position " + cursor.position());
        }

        if ("#".equals(head) || "!".equals(head)) {
            String value = parseComment(cursor);
            return new CommentNode(value);
        }
        if (".".equals(head)) {
            return parseDocumentNode(cursor);
        }
        if (head.startsWith("?")) {
            PiNode pi = parseProcessingInstruction(cursor, head.substring(1));
            return pi;
        }
        if (head.startsWith("@")) {
            throw new IOException("Legacy @-attribute syntax not supported at position " + cursor.position());
        }
        if (XDM_MAP_HEAD.equals(head)) {
            return parseXdmMapNode(cursor);
        }
        if (XDM_ARRAY_HEAD.equals(head)) {
            return parseXdmArrayNode(cursor);
        }
        if (isTypedAtomicHead(head)) {
            return parseTypedAtomic(cursor, head);
        }

        ElementNode node = new ElementNode(head);
        boolean hasStructuredContent = false;
        while (true) {
            cursor.skipTrivia();
            if (cursor.isEof()) {
                throw new IOException("Unexpected end of input while parsing node '" + head + "'");
            }
            char ch = cursor.peek();
            if (ch == ')') {
                cursor.next();
                return node;
            }
            if (ch == '(') {
                Item child = parseItem(cursor);
                node.children.add(child);
                hasStructuredContent = true;
                continue;
            }
            if (ch == '[') {
                throw new IOException("Sequence block must be wrapped in (xdm:array [...]) at position " + cursor.position());
            }
            if (ch == '{') {
                if (hasStructuredContent) {
                    throw new IOException("Associative block in element body allowed only before child nodes at position " + cursor.position());
                }
                MapNode map = parseMap(cursor);
                if (!hasStructuredContent && isAttributeNamespaceBlock(map)) {
                    applyAttributeNamespaceBlock(node, map);
                } else {
                    throw new IOException("Use (xdm:map {...}) for map child nodes at position " + cursor.position());
                }
                continue;
            }
            if (ch == '"') {
                node.children.add(new TextNode(cursor.readString()));
                hasStructuredContent = true;
                continue;
            }
            throw new IOException("Unexpected token in element body at position " + cursor.position());
        }
    }

    private DocumentNode parseDocumentNode(Cursor cursor) throws IOException {
        XmlDeclarationNode xmlDeclaration = null;
        List<Item> children = new ArrayList<>();
        while (true) {
            cursor.skipTrivia();
            if (cursor.isEof()) {
                throw new IOException("Unexpected end of input in document node");
            }
            if (cursor.peek() == ')') {
                cursor.next();
                return new DocumentNode(xmlDeclaration, children);
            }
            if (xmlDeclaration == null && children.isEmpty() && cursor.peek() == '{') {
                MapNode mapNode = parseMap(cursor);
                if (!isXmlDeclarationMap(mapNode)) {
                    throw new IOException("Document declaration map supports only version/encoding/standalone keys");
                }
                xmlDeclaration = toXmlDeclaration(mapNode);
                continue;
            }
            Item child = parseItem(cursor);
            children.add(child);
        }
    }

    private MapNode parseXdmMapNode(Cursor cursor) throws IOException {
        cursor.skipTrivia();
        if (cursor.isEof() || cursor.peek() != '{') {
            throw new IOException("xdm:map requires associative payload block");
        }
        MapNode map = parseMap(cursor);
        cursor.skipTrivia();
        cursor.expect(')');
        return map;
    }

    private ArrayNode parseXdmArrayNode(Cursor cursor) throws IOException {
        cursor.skipTrivia();
        if (cursor.isEof() || cursor.peek() != '[') {
            throw new IOException("xdm:array requires sequence payload block");
        }
        ArrayNode array = parseArray(cursor);
        cursor.skipTrivia();
        cursor.expect(')');
        return array;
    }

    private boolean isXmlDeclarationMap(MapNode map) {
        if (map.entries.isEmpty()) {
            return false;
        }
        boolean hasVersion = false;
        for (MapEntry entry : map.entries) {
            if (!XML_DECLARATION_KEYS.contains(entry.key)) {
                return false;
            }
            if (!(entry.value instanceof AtomicNode)) {
                return false;
            }
            if ("version".equals(entry.key)) {
                hasVersion = true;
            }
        }
        return hasVersion;
    }

    private XmlDeclarationNode toXmlDeclaration(MapNode map) {
        String version = null;
        String encoding = null;
        String standalone = null;
        for (MapEntry entry : map.entries) {
            AtomicNode value = (AtomicNode) entry.value;
            switch (entry.key) {
                case "version" -> version = value.value;
                case "encoding" -> encoding = value.value;
                case "standalone" -> standalone = value.value;
                default -> {
                    // guarded by isXmlDeclarationMap
                }
            }
        }
        return new XmlDeclarationNode(version, encoding, standalone);
    }

    private TypedAtomicNode parseTypedAtomic(Cursor cursor, String typeName) throws IOException {
        cursor.skipTrivia();
        if (cursor.isEof()) {
            throw new IOException("Unexpected end of input in typed atomic value '" + typeName + "'");
        }
        AtomicNode value;
        if (cursor.peek() == '"') {
            value = new AtomicNode(cursor.readString(), true);
        } else {
            String symbol = cursor.readSymbol();
            if (symbol.isBlank()) {
                throw new IOException("Typed atomic value missing for '" + typeName + "' at position " + cursor.position());
            }
            value = new AtomicNode(symbol, false);
        }
        cursor.skipTrivia();
        cursor.expect(')');
        return new TypedAtomicNode(typeName, value);
    }

    private boolean isTypedAtomicHead(String head) {
        return SUPPORTED_TYPED_ATOMICS.contains(head);
    }

    private String parseComment(Cursor cursor) throws IOException {
        cursor.skipTrivia();
        if (cursor.peek() != '"') {
            throw new IOException("Comment value must be quoted string at position " + cursor.position());
        }
        String value = cursor.readString();
        cursor.skipTrivia();
        cursor.expect(')');
        return value;
    }

    private PiNode parseProcessingInstruction(Cursor cursor, String target) throws IOException {
        if (target.isBlank()) {
            throw new IOException("Processing instruction target missing at position " + cursor.position());
        }

        List<PiToken> tokens = new ArrayList<>();
        while (true) {
            cursor.skipTrivia();
            if (cursor.isEof()) {
                throw new IOException("Unexpected end of input in processing instruction '" + target + "'");
            }
            if (cursor.peek() == ')') {
                cursor.next();
                return new PiNode(target, tokens);
            }

            if (cursor.peek() == '{') {
                MapNode map = parseMap(cursor);
                for (MapEntry entry : map.entries) {
                    if (!(entry.value instanceof AtomicNode atomic)) {
                        throw new IOException("Processing instruction value for key '" + entry.key + "' must be atomic");
                    }
                    tokens.add(new PiToken(entry.key, atomic.value));
                }
                cursor.skipTrivia();
                cursor.expect(')');
                return new PiNode(target, tokens);
            }

            String key = cursor.readSymbol();
            if (key.isEmpty()) {
                throw new IOException("Processing instruction key missing at position " + cursor.position());
            }
            cursor.expect('=');
            if (cursor.peek() != '"') {
                throw new IOException("Processing instruction value must be quoted at position " + cursor.position());
            }
            String value = cursor.readString();
            tokens.add(new PiToken(key, value));
        }
    }

    private MapNode parseMap(Cursor cursor) throws IOException {
        cursor.expect('{');
        List<MapEntry> entries = new ArrayList<>();
        while (true) {
            cursor.skipTrivia();
            if (cursor.isEof()) {
                throw new IOException("Unexpected end of input while parsing map");
            }
            if (cursor.peek() == '}') {
                cursor.next();
                return new MapNode(entries);
            }

            String key;
            if (cursor.peek() == '"') {
                key = cursor.readString();
            } else {
                key = cursor.readSymbol();
            }
            if (key.isBlank()) {
                throw new IOException("Map key missing at position " + cursor.position());
            }

            cursor.skipTrivia();
            Item value = parseScalarOrStructured(cursor);
            entries.add(new MapEntry(key, value));
        }
    }

    private ArrayNode parseArray(Cursor cursor) throws IOException {
        cursor.expect('[');
        List<Item> items = new ArrayList<>();
        while (true) {
            cursor.skipTrivia();
            if (cursor.isEof()) {
                throw new IOException("Unexpected end of input while parsing array");
            }
            if (cursor.peek() == ']') {
                cursor.next();
                return new ArrayNode(items);
            }
            items.add(parseScalarOrStructured(cursor));
        }
    }

    private Item parseScalarOrStructured(Cursor cursor) throws IOException {
        cursor.skipTrivia();
        if (cursor.isEof()) {
            throw new IOException("Unexpected end of input while parsing value");
        }
        char ch = cursor.peek();
        if (ch == '(') {
            return parseItem(cursor);
        }
        if (ch == '{') {
            return parseMap(cursor);
        }
        if (ch == '[') {
            return parseArray(cursor);
        }
        if (ch == '"') {
            return new AtomicNode(cursor.readString(), true);
        }
        String symbol = cursor.readSymbol();
        if (symbol.isBlank()) {
            throw new IOException("Value token missing at position " + cursor.position());
        }
        return new AtomicNode(symbol, false);
    }

    private boolean isAttributeNamespaceBlock(MapNode map) {
        if (map.entries.isEmpty()) {
            return false;
        }
        for (MapEntry entry : map.entries) {
            if (!(entry.value instanceof AtomicNode atomic) || !atomic.quoted) {
                return false;
            }
        }
        return true;
    }

    private void applyAttributeNamespaceBlock(ElementNode node, MapNode map) {
        for (MapEntry entry : map.entries) {
            AtomicNode value = (AtomicNode) entry.value;
            if ("xmlns".equals(entry.key)) {
                node.namespaceDeclarations.add(new NamespaceDecl("", value.value));
                continue;
            }
            if (entry.key.startsWith("xmlns:")) {
                String prefix = entry.key.substring("xmlns:".length());
                node.namespaceDeclarations.add(new NamespaceDecl(prefix, value.value));
                continue;
            }
            node.attributes.put(entry.key, value.value);
        }
    }


    private void emitElement(ElementNode node, ContentHandler handler, LexicalHandler lexical, Deque<Map<String, String>> namespaceStack) throws SAXException {
        Map<String, String> parent = namespaceStack.isEmpty() ? Map.of() : namespaceStack.peek();
        Map<String, String> current = new LinkedHashMap<>(parent);

        for (NamespaceDecl declaration : node.namespaceDeclarations) {
            String prefix = declaration.prefix;
            String uri = declaration.uri;
            handler.startPrefixMapping(prefix, uri);
            current.put(prefix, uri);
        }

        String elementPrefix = prefixOf(node.name);
        String elementUri = current.getOrDefault(elementPrefix, "");
        String elementLocal = localNameOf(node.name);

        AttributesImpl attributes = new AttributesImpl();
        for (Map.Entry<String, String> entry : node.attributes.entrySet()) {
            String attrName = entry.getKey();
            String attrPrefix = prefixOf(attrName);
            String attrUri = attrPrefix.isEmpty() ? "" : current.getOrDefault(attrPrefix, "");
            String attrLocal = localNameOf(attrName);
            attributes.addAttribute(attrUri, attrLocal, attrName, "CDATA", entry.getValue());
        }

        namespaceStack.push(current);
        handler.startElement(elementUri, elementLocal, node.name, attributes);

        for (Item child : node.children) {
            if (child instanceof TextNode textNode) {
                if (textNode.value.isBlank()) {
                    continue;
                }
                char[] chars = textNode.value.toCharArray();
                handler.characters(chars, 0, chars.length);
            } else if (child instanceof AtomicNode atomicNode) {
                emitAtomic(atomicNode, handler);
            } else if (child instanceof ElementNode nested) {
                emitElement(nested, handler, lexical, namespaceStack);
            } else if (child instanceof MapNode mapNode) {
                emitMap(mapNode, handler, lexical, namespaceStack);
            } else if (child instanceof ArrayNode arrayNode) {
                emitArray(arrayNode, handler, lexical, namespaceStack);
            } else if (child instanceof TypedAtomicNode typedAtomicNode) {
                emitTypedAtomic(typedAtomicNode, handler);
            } else if (child instanceof DocumentNode documentNode) {
                for (Item nestedChild : documentNode.children) {
                    emitItemAtDocumentLevel(nestedChild, handler, lexical, namespaceStack);
                }
            } else if (child instanceof CommentNode commentNode) {
                if (lexical != null) {
                    char[] chars = commentNode.value.toCharArray();
                    lexical.comment(chars, 0, chars.length);
                }
            } else if (child instanceof PiNode piNode) {
                handler.processingInstruction(piNode.target, toPiData(piNode.tokens));
            }
        }

        handler.endElement(elementUri, elementLocal, node.name);
        namespaceStack.pop();

        for (int i = node.namespaceDeclarations.size() - 1; i >= 0; i--) {
            handler.endPrefixMapping(node.namespaceDeclarations.get(i).prefix);
        }
    }

    private void emitDocumentRoot(Item root, ContentHandler handler, LexicalHandler lexical, Deque<Map<String, String>> namespaceStack) throws SAXException {
        if (root instanceof DocumentNode documentNode) {
            if (documentNode.xmlDeclaration != null) {
                emitXmlDeclaration(documentNode.xmlDeclaration, handler);
            }
            for (Item item : documentNode.children) {
                emitItemAtDocumentLevel(item, handler, lexical, namespaceStack);
            }
            return;
        }
        emitItemAtDocumentLevel(root, handler, lexical, namespaceStack);
    }

    private void emitItemAtDocumentLevel(Item item, ContentHandler handler, LexicalHandler lexical, Deque<Map<String, String>> namespaceStack) throws SAXException {
        if (item instanceof ElementNode elementNode) {
            emitElement(elementNode, handler, lexical, namespaceStack);
            return;
        }
        if (item instanceof CommentNode commentNode) {
            if (lexical != null) {
                char[] chars = commentNode.value.toCharArray();
                lexical.comment(chars, 0, chars.length);
            }
            return;
        }
        if (item instanceof PiNode piNode) {
            handler.processingInstruction(piNode.target, toPiData(piNode.tokens));
            return;
        }
        if (item instanceof TextNode textNode) {
            char[] chars = textNode.value.toCharArray();
            handler.characters(chars, 0, chars.length);
            return;
        }
        if (item instanceof AtomicNode atomicNode) {
            emitAtomic(atomicNode, handler);
            return;
        }
        if (item instanceof MapNode mapNode) {
            emitMap(mapNode, handler, lexical, namespaceStack);
            return;
        }
        if (item instanceof ArrayNode arrayNode) {
            emitArray(arrayNode, handler, lexical, namespaceStack);
            return;
        }
        if (item instanceof TypedAtomicNode typedAtomicNode) {
            emitTypedAtomic(typedAtomicNode, handler);
        }
    }

    private void emitMap(MapNode map, ContentHandler handler, LexicalHandler lexical, Deque<Map<String, String>> namespaceStack) throws SAXException {
        AttributesImpl attrs = new AttributesImpl();
        startInternalElement("map", attrs, handler);
        for (MapEntry entry : map.entries) {
            AttributesImpl entryAttrs = new AttributesImpl();
            entryAttrs.addAttribute("", "key", "key", "CDATA", entry.key);
            startInternalElement("entry", entryAttrs, handler);
            emitInternalValue(entry.value, handler, lexical, namespaceStack);
            endInternalElement("entry", handler);
        }
        endInternalElement("map", handler);
    }

    private void emitArray(ArrayNode array, ContentHandler handler, LexicalHandler lexical, Deque<Map<String, String>> namespaceStack) throws SAXException {
        AttributesImpl attrs = new AttributesImpl();
        startInternalElement("array", attrs, handler);
        for (Item value : array.items) {
            startInternalElement("item", new AttributesImpl(), handler);
            emitInternalValue(value, handler, lexical, namespaceStack);
            endInternalElement("item", handler);
        }
        endInternalElement("array", handler);
    }

    private void emitTypedAtomic(TypedAtomicNode typedAtomic, ContentHandler handler) throws SAXException {
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "type", "type", "CDATA", typedAtomic.typeName);
        attrs.addAttribute("", "value", "value", "CDATA", typedAtomic.value.value);
        attrs.addAttribute("", "quoted", "quoted", "CDATA", String.valueOf(typedAtomic.value.quoted));
        startInternalElement("typed-atomic", attrs, handler);
        endInternalElement("typed-atomic", handler);
    }

    private void emitAtomic(AtomicNode atomic, ContentHandler handler) throws SAXException {
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "value", "value", "CDATA", atomic.value);
        attrs.addAttribute("", "quoted", "quoted", "CDATA", String.valueOf(atomic.quoted));
        startInternalElement("literal", attrs, handler);
        endInternalElement("literal", handler);
    }

    private void emitInternalValue(Item value, ContentHandler handler, LexicalHandler lexical, Deque<Map<String, String>> namespaceStack) throws SAXException {
        if (value instanceof ElementNode elementNode) {
            emitElement(elementNode, handler, lexical, namespaceStack);
            return;
        }
        if (value instanceof MapNode mapNode) {
            emitMap(mapNode, handler, lexical, namespaceStack);
            return;
        }
        if (value instanceof ArrayNode arrayNode) {
            emitArray(arrayNode, handler, lexical, namespaceStack);
            return;
        }
        if (value instanceof TypedAtomicNode typedAtomicNode) {
            emitTypedAtomic(typedAtomicNode, handler);
            return;
        }
        if (value instanceof AtomicNode atomicNode) {
            emitAtomic(atomicNode, handler);
            return;
        }
        if (value instanceof TextNode textNode) {
            AtomicNode atomicNode = new AtomicNode(textNode.value, true);
            emitAtomic(atomicNode, handler);
            return;
        }
        if (value instanceof CommentNode commentNode) {
            if (lexical != null) {
                char[] chars = commentNode.value.toCharArray();
                lexical.comment(chars, 0, chars.length);
            }
            return;
        }
        if (value instanceof PiNode piNode) {
            handler.processingInstruction(piNode.target, toPiData(piNode.tokens));
            return;
        }
        if (value instanceof DocumentNode documentNode) {
            if (documentNode.xmlDeclaration != null) {
                emitXmlDeclaration(documentNode.xmlDeclaration, handler);
            }
            for (Item child : documentNode.children) {
                emitInternalValue(child, handler, lexical, namespaceStack);
            }
        }
    }

    private void emitXmlDeclaration(XmlDeclarationNode declaration, ContentHandler handler) throws SAXException {
        AttributesImpl attrs = new AttributesImpl();
        attrs.addAttribute("", "version", "version", "CDATA", declaration.version);
        if (declaration.encoding != null) {
            attrs.addAttribute("", "encoding", "encoding", "CDATA", declaration.encoding);
        }
        if (declaration.standalone != null) {
            attrs.addAttribute("", "standalone", "standalone", "CDATA", declaration.standalone);
        }
        startInternalElement("xml-decl", attrs, handler);
        endInternalElement("xml-decl", handler);
    }

    private void startInternalElement(String localName, AttributesImpl attributes, ContentHandler handler) throws SAXException {
        String qName = INTERNAL_XDM_PREFIX + ":" + localName;
        handler.startPrefixMapping(INTERNAL_XDM_PREFIX, INTERNAL_XDM_URI);
        handler.startElement(INTERNAL_XDM_URI, localName, qName, attributes);
    }

    private void endInternalElement(String localName, ContentHandler handler) throws SAXException {
        String qName = INTERNAL_XDM_PREFIX + ":" + localName;
        handler.endElement(INTERNAL_XDM_URI, localName, qName);
        handler.endPrefixMapping(INTERNAL_XDM_PREFIX);
    }

    private String toPiData(List<PiToken> tokens) {
        StringBuilder sb = new StringBuilder();
        for (PiToken token : tokens) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(token.key)
                .append('=')
                .append(quote(token.value));
        }
        return sb.toString();
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

    private String prefixOf(String qName) {
        int index = qName.indexOf(':');
        if (index <= 0) {
            return "";
        }
        return qName.substring(0, index);
    }

    private String localNameOf(String qName) {
        int index = qName.indexOf(':');
        if (index < 0 || index + 1 >= qName.length()) {
            return qName;
        }
        return qName.substring(index + 1);
    }

    private sealed interface Item permits ElementNode, TextNode, CommentNode, PiNode, DocumentNode, MapNode, ArrayNode, AtomicNode, TypedAtomicNode {
    }

    private static final class ElementNode implements Item {
        private final String name;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<NamespaceDecl> namespaceDeclarations = new ArrayList<>();
        private final List<Item> children = new ArrayList<>();

        private ElementNode(String name) {
            this.name = name;
        }
    }

    private record TextNode(String value) implements Item {
    }

    private record CommentNode(String value) implements Item {
    }

    private record PiNode(String target, List<PiToken> tokens) implements Item {
    }

    private record DocumentNode(XmlDeclarationNode xmlDeclaration, List<Item> children) implements Item {
    }

    private record XmlDeclarationNode(String version, String encoding, String standalone) {
    }

    private record MapNode(List<MapEntry> entries) implements Item {
    }

    private record ArrayNode(List<Item> items) implements Item {
    }

    private record AtomicNode(String value, boolean quoted) implements Item {
    }

    private record TypedAtomicNode(String typeName, AtomicNode value) implements Item {
    }

    private record MapEntry(String key, Item value) {
    }

    private record NamespaceDecl(String prefix, String uri) {
    }

    private record PiToken(String key, String value) {
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

        private int position() {
            return index;
        }


        private char peek() {
            return source.charAt(index);
        }

        private char next() {
            return source.charAt(index++);
        }

        private void expect(char expected) throws IOException {
            if (isEof() || next() != expected) {
                throw new IOException("Expected '" + expected + "' at position " + index);
            }
        }

        private void skipTrivia() {
            while (!isEof()) {
                char ch = peek();
                if (Character.isWhitespace(ch)) {
                    index++;
                    continue;
                }
                if (ch == ';') {
                    while (!isEof() && peek() != '\n') {
                        index++;
                    }
                    continue;
                }
                return;
            }
        }

        private String readSymbol() {
            int start = index;
            while (!isEof()) {
                char ch = peek();
                if (Character.isWhitespace(ch)
                    || ch == '('
                    || ch == ')'
                    || ch == '{'
                    || ch == '}'
                    || ch == '['
                    || ch == ']'
                    || ch == '"'
                    || ch == '=') {
                    break;
                }
                index++;
            }
            return source.substring(start, index);
        }

        private String readString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!isEof()) {
                char ch = next();
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    if (isEof()) {
                        throw new IOException("Unexpected end of input in string escape");
                    }
                    char escaped = next();
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
            throw new IOException("Unterminated string literal at position " + index);
        }
    }
}


