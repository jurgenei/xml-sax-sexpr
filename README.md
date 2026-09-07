# xml-sax-sexpr

[![Build](https://github.com/jurgenei/xml-sax-sexpr/actions/workflows/ci.yml/badge.svg?branch=release/0.1.0)](https://github.com/jurgenei/xml-sax-sexpr/actions/workflows/ci.yml?query=branch%3Arelease%2F0.1.0)
[![Release](https://github.com/jurgenei/xml-sax-sexpr/actions/workflows/release.yml/badge.svg?branch=release/0.1.0)](https://github.com/jurgenei/xml-sax-sexpr/actions/workflows/release.yml?query=branch%3Arelease%2F0.1.0)
[![Coverage](https://github.com/jurgenei/xml-sax-sexpr/actions/workflows/coverage.yml/badge.svg?branch=release/0.1.0)](https://github.com/jurgenei/xml-sax-sexpr/actions/workflows/coverage.yml?query=branch%3Arelease%2F0.1.0)
[![Maven Central](https://img.shields.io/maven-central/v/name.jurgenei.xml/sax-sexpr.svg)](https://search.maven.org/artifact/name.jurgenei.xml/sax-sexpr)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

SAX parser, serializer, XMLReader for bracket-based XML/XDM S-expression format.

## Coordinates

```text
name.jurgenei.xml:sax-sexpr:<version>
```

## Namespace

- Java package: `name.jurgenei.xml.sexpr`
- Internal XDM bridge namespace URI: `urn:name.jurgenei.xml:xdm`
- Internal XDM bridge prefix: `xdm`

## Included APIs

- `SExpressionParser` - parses S-expression input into SAX events
- `SExpressionSerializer` - renders SAX events as canonical or legacy S-expression
- `SExpressionXmlReader` - `XMLReader` facade over parser

## Canonical syntax excerpt

- `()` nodes
- `{}` associative structures
- `[]` sequences
- `.` document head
- `?` processing instruction head
- `!` comment head

Examples:

- `(book (title "XML"))`
- `(book { id "b1" xmlns:m "urn:math" } (m:title "XML"))`
- `(. { version "1.0" encoding "UTF-8" } (book))`
- `(xdm:map { name "John" age 42 })`
- `(xdm:array [ "A" "B" "C" ])`
- `(xs:boolean true)`

## Build and test

```bash
./gradlew clean test
./gradlew coverage
```

## GitHub Actions

- `ci.yml` runs build and unit tests on push and pull request
- `coverage.yml` runs coverage task and uploads JaCoCo report artifact
- `release.yml` publishes signed artifacts to Sonatype OSSRH

## Maven Central publishing

### Local release prereqs

1. Sonatype credentials (`ossrhUsername`, `ossrhPassword`) in `~/.gradle/gradle.properties`
2. GPG key installed locally (`gpg --list-secret-keys` shows signing key)
3. Project version without `-SNAPSHOT`

Example `~/.gradle/gradle.properties`:

```properties
ossrhUsername=YOUR_SONATYPE_USERNAME
ossrhPassword=YOUR_SONATYPE_PASSWORD
signing.gnupg.keyName=YOUR_GPG_KEY_ID
signing.gnupg.passphrase=YOUR_GPG_PASSPHRASE
```

Publish:

```bash
./gradlew clean publish
```

### CI release secrets

Configure repository secrets for `release.yml`:

- `OSSRH_USERNAME`
- `OSSRH_PASSWORD`
- `SIGNING_KEY` (ASCII-armored private key)
- `SIGNING_PASSWORD`
- `SIGNING_KEY_ID` (optional)

## License

[MIT](LICENSE)

