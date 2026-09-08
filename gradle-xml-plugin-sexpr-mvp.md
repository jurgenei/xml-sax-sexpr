# gradle-xml-plugin: Minimal S-Expression Support MVP

## Objective

Add first-class S-expression input/output support to the existing Saxon infrastructure with the smallest possible implementation.

Do not add:

- Semantic MathML rewriting
- GraphML rewriting
- Neo4j integration
- URI schemes
- Custom Saxon tree models
- iXML support

Only implement lossless S-expression serialization/deserialization and Gradle integration.

---

## Scope

### Read

```text
.sexpr
   ↓
SExpressionParser
   ↓
SAXSource
   ↓
Saxon
```

### Write

```text
Saxon XDM
     ↓
SExpressionSerializer
     ↓
.sexpr
```

---

## Canonical Format

### Elements

XML

```xml
<book>
  <title>XML</title>
</book>
```

S-expression

```lisp
(book
  (title "XML"))
```

### Attributes

XML

```xml
<book id="b1"/>
```

S-expression

```lisp
(book
  [id "b1"])
```

### Mixed example

XML

```xml
<book id="b1">
  <title>XML</title>
</book>
```

S-expression

```lisp
(book
  [id "b1"]
  (title "XML"))
```

---

## Java Components

### SExpressionParser

Input:

```lisp
(book [id "b1"] (title "XML"))
```

Output:

SAX events.

Public API:

```java
class SExpressionParser {
    void parse(Reader reader, ContentHandler handler);
}
```

---

### SExpressionXmlReader

Adapter from parser to SAXSource.

```java
class SExpressionXmlReader implements XMLReader
```

Usage:

```java
Source source = new SAXSource(
    new SExpressionXmlReader(),
    new InputSource(reader)
);
```

---

### SExpressionSerializer

Consumes SAX events.

```java
class SExpressionSerializer
    implements ContentHandler
```

Output:

```lisp
(book
  [id "b1"]
  (title "XML"))
```

---

## Gradle Integration

### New Document Type

```kotlin
enum class XmlDocumentType {
    XML,
    SEXPR
}
```

---

### Input

Allow:

```kotlin
xslt {
    input.set(file("input.sexpr"))
}
```

When extension is:

```text
.sexpr
```

create:

```java
SAXSource(SExpressionXmlReader)
```

instead of XML parser.

---

### Output

Allow:

```kotlin
xslt {
    output.set(file("output.sexpr"))
}
```

When extension is:

```text
.sexpr
```

attach:

```java
SExpressionSerializer
```

instead of XML serializer.

---

## Acceptance Criteria

Roundtrip succeeds:

```text
input.xml
   ↓
xmlToSexpr
   ↓
a.sexpr
   ↓
sexprToXml
   ↓
b.xml
```

And:

```text
canonicalize(input.xml)
==
canonicalize(b.xml)
```

No semantic loss.

---

## Deliverables

1. SExpressionParser
2. SExpressionXmlReader
3. SExpressionSerializer
4. Gradle file-extension based Source selection
5. Gradle file-extension based Result selection
6. Roundtrip integration test

Everything else is out of scope.
