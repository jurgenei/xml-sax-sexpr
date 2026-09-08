# S-XDM Reference Implementation Specification

## Version 0.1 Draft
### XML/SAX ↔ Internal Model ↔ S-XDM Serializer/Parser

## 1. Purpose

This document specifies the first reference implementation of S-XDM.

The objective is not to implement a new XML stack.

The objective is to demonstrate that S-XDM is a complete, lossless serialization of XDM.

## 2. Scope

The implementation SHALL support:

- XML Documents
- XDM Node Types
- Namespaces
- Comments
- Processing Instructions
- Text Nodes
- Maps
- Arrays
- Typed Atomic Values

## 3. Architecture

XML/SAX → Internal Model → S-XDM

S-XDM → Internal Model → XML/SAX

Phase 1 canonical model is implementation-defined and MUST preserve supported constructs losslessly.

## 4. Components

### S-XDM Writer

Converts internal model/events to S-XDM.

### S-XDM Reader

Converts S-XDM to internal model/events.

### XML Import Layer

Builds internal model/events from XML using standard SAX/JAXP APIs.

### XML Export Layer

Emits XML using standard SAX/JAXP transformer/serializer pipeline.

### 4.1 Implementation Profiles

#### Core Profile (Required)

- JRE + JAXP/SAX only
- No vendor-specific runtime required
- Lossless support for all constructs in section 2

#### Saxon Profile (Optional)

- Uses Saxon for XDM-native bridges when desired
- May provide tighter alignment with vendor XDM APIs

## 5. Supported XDM Constructs

### Document Node

```lisp
(. ...)
```

### Element Node

```lisp
(book ...)
```

### Text Node

```lisp
"Hello"
```

### Comment Node

```lisp
(! "Comment")
```

### Processing Instruction

```lisp
(?xml-stylesheet
  { href "main.xsl" })
```

### Attributes

```lisp
(customer { id "123" })
```

### Namespaces

```lisp
(book
  { xmlns:m "urn:math" }
  (m:formula))
```

### Maps

```lisp
(xdm:map { name "John" age 42 })
```

### Arrays

```lisp
(xdm:array [ "A" "B" "C" ])
```

## 6. Disambiguation Rules

- `(qname ...)` denotes an XML element node.
- `(. ...)` denotes document node.
- `(! "text")` denotes comment node.
- `(?target { ... })` denotes processing instruction node.

Reserved typed heads for non-element XDM structures:

- `(xdm:map { key value ... })` denotes XDM map.
- `(xdm:array [ item ... ])` denotes XDM array.

Container token classes:

- `{ ... }` is associative payload container.
- `[ ... ]` is sequence payload container.

Therefore:

- `(map ...)` is always XML element named `map`.
- `(array ...)` is always XML element named `array`.
- XDM map/array MUST use `xdm:map`/`xdm:array` heads.

## 7. Parsing Model

Lexer → Parser → AST → Internal Builder

## 8. Namespace Resolution

Namespace scope shall be maintained during parsing and serialization.

## 9. Typed Atomic Values

Initially supported:

- xs:string
- xs:boolean
- xs:integer
- xs:decimal
- xs:double

Future examples:

```lisp
(xs:date "2026-09-06")
(xs:dateTime "2026-09-06T12:00:00")
```

## 10. Roundtrip Test Suite

The following must roundtrip losslessly through internal model:

- Elements
- Attributes
- Namespaces
- Comments
- Processing Instructions
- Maps
- Arrays

## 11. Success Criteria

- XML → S-XDM works
- S-XDM → XML works
- S-XDM roundtrip fidelity proven for required constructs
- Vendor-agnostic XML import/export works
- Namespace handling works
- Maps work
- Arrays work
- Automated tests pass

## 12. Phase 2

- S-XSD
- S-XSLT
- XPath expression syntax
- Function items
- Schema-aware processing
- AI evaluation benchmark
