package org.bittrace.plugin.format

/**
 * Semantic token classes a [BodyFormatter] can tag its output with. Formatters
 * never pick colours: the host maps each kind onto the active theme's palette,
 * so highlighting recolours with the rest of the UI when the theme changes.
 */
enum class TokenKind {
    /** Unclassified text. */
    PLAIN,

    /** Line and block comments. */
    COMMENT,

    /** Quoted string literals, including attribute values. */
    STRING,

    /** Numeric literals. */
    NUMBER,

    /** `true` / `false` / `null` and friends. */
    LITERAL,

    /** Language keywords (`const`, `query`, `interface`, …). */
    KEYWORD,

    /** Object keys, GraphQL fields, member names. */
    PROPERTY,

    /** Braces, brackets, operators, separators. */
    PUNCTUATION,

    /** Markup element names. */
    TAG,

    /** Markup attribute names and GraphQL argument names. */
    ATTRIBUTE,

    /** `$variables`. */
    VARIABLE,

    /** Type names. */
    TYPE,

    /** Doctypes, processing instructions, directives, hex-dump offsets. */
    META,
}

/**
 * A half-open `[start, end)` range of [FormattedBody.text] classified as [kind].
 */
class Span(val start: Int, val end: Int, val kind: TokenKind)

/**
 * Formatter output: the text to display plus the spans to colour. Spans must be
 * ordered, non-overlapping and within [text]; the host ignores any that aren't,
 * and an empty [spans] simply renders as unhighlighted text.
 */
class FormattedBody(val text: String, val spans: List<Span> = emptyList())
