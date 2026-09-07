package org.bittrace.ui.components.editor

import com.monkopedia.kodemirror.autocomplete.Completion
import com.monkopedia.kodemirror.autocomplete.CompletionResult
import com.monkopedia.kodemirror.autocomplete.CompletionConfig
import com.monkopedia.kodemirror.autocomplete.CompletionSource
import com.monkopedia.kodemirror.autocomplete.autocompletion
import com.monkopedia.kodemirror.language.LanguageSupport
import com.monkopedia.kodemirror.language.StreamLanguage
import com.monkopedia.kodemirror.language.StreamParser
import com.monkopedia.kodemirror.language.StringStream

/**
 * GraphQL, as a stream parser.
 *
 * Written here because upstream has 22 language modules and GraphQL is not one
 * of them — and because a stream parser is the right shape for it. GraphQL's
 * lexical grammar is genuinely small: comments, two kinds of string, numbers,
 * punctuation, and names. What needs *context* is only which colour a name
 * takes, and one boolean carries that.
 *
 * The state is deliberately two flags rather than a parse tree. A name is a type
 * when it follows `:` or `on`, and a field otherwise; that rule is wrong for
 * about the same cases GraphiQL's own highlighting is wrong for, and getting it
 * right would mean parsing the document on every keystroke to colour it.
 */
data class GraphQlState(
    /** Inside a `"""` block string, which is the only construct spanning lines. */
    var inBlockString: Boolean = false,
    /** The next name is a type: we just passed a `:` or an `on`. */
    var expectType: Boolean = false,
)

/** The stream parser. Token names are the ones `StreamParser` resolves to tags. */
val graphQlParser: StreamParser<GraphQlState> = object : StreamParser<GraphQlState> {

    override val name: String get() = "graphql"

    override fun startState(indentUnit: Int) = GraphQlState()

    override fun copyState(state: GraphQlState) = state.copy()

    override val languageData: Map<String, Any>
        get() = mapOf(
            // What Ctrl+/ inserts, and what the folding and bracket logic treat
            // as a comment rather than as content.
            "commentTokens" to mapOf("line" to "#"),
            "closeBrackets" to mapOf("brackets" to listOf("(", "[", "{", "\"")),
        )

    override fun token(stream: StringStream, state: GraphQlState): String? {
        if (state.inBlockString) return blockString(stream, state)
        if (stream.eatSpace()) return null

        // A comment runs to the end of the line, and there is only one kind.
        if (stream.peek() == "#") {
            stream.skipToEnd()
            return "comment"
        }

        if (stream.match("\"\"\"")) {
            state.inBlockString = true
            return blockString(stream, state)
        }
        if (stream.peek() == "\"") {
            stream.next()
            return quotedString(stream)
        }

        val ch = stream.peek() ?: return null

        if (ch[0].isDigit() || (ch == "-" && stream.string.getOrNull(stream.pos + 1)?.isDigit() == true)) {
            stream.next()
            stream.eatWhile { it.length == 1 && (it[0].isDigit() || it[0] in ".eE+-") }
            return "number"
        }

        // `$name` is a variable and `@name` a directive. Both are a sigil plus a
        // name, and neither can be anything else, so neither needs the lookahead
        // below.
        if (ch == "$" || ch == "@") {
            stream.next()
            stream.eatWhile { it.length == 1 && isNameChar(it[0]) }
            return if (ch == "$") "variableName" else "keyword"
        }

        if (isNameStart(ch[0])) return name(stream, state)

        stream.next()
        return punctuation(ch[0], state)
    }

    /** Consumes to the closing `"""`, staying in the state until it finds one. */
    private fun blockString(stream: StringStream, state: GraphQlState): String {
        while (!stream.eol()) {
            if (stream.match("\"\"\"")) {
                state.inBlockString = false
                return "string"
            }
            stream.next()
        }
        return "string"
    }

    /** A single-line string, ended by an unescaped quote or by the line. */
    private fun quotedString(stream: StringStream): String {
        var escaped = false
        while (!stream.eol()) {
            val c = stream.next() ?: break
            if (escaped) {
                escaped = false
            } else if (c == "\\") {
                escaped = true
            } else if (c == "\"") {
                break
            }
        }
        return "string"
    }

    /**
     * A name, coloured by what it is being used as.
     *
     * The lookahead is read-only — it walks [StringStream.string] rather than
     * consuming — because deciding that `id` is a field requires seeing the `:`
     * that is not part of it.
     */
    private fun name(stream: StringStream, state: GraphQlState): String {
        val start = stream.pos
        stream.eatWhile { it.length == 1 && isNameChar(it[0]) }
        val word = stream.string.substring(start, stream.pos)

        val wasExpectingType = state.expectType
        state.expectType = word == "on"

        return when {
            word in GRAPHQL_LITERALS -> if (word == "null") "null" else "bool"
            word in GRAPHQL_KEYWORDS -> "keyword"
            // A type: named after `:` or after `on`.
            wasExpectingType -> "typeName"
            // An argument or an alias — anything with a colon after it.
            nextNonSpace(stream) == ':' -> "propertyName"
            else -> "variableName"
        }
    }

    /**
     * Punctuation, and the one place [GraphQlState.expectType] is set.
     *
     * `:` introduces a type in a variable definition and a value in an argument
     * list; treating both as a type is wrong for the second, and is what keeps
     * this a lexer instead of a parser.
     */
    private fun punctuation(c: Char, state: GraphQlState): String? = when (c) {
        ':' -> {
            state.expectType = true
            "punctuation"
        }

        '{', '}' -> {
            state.expectType = false
            "brace"
        }

        '(', ')' -> {
            state.expectType = false
            "paren"
        }

        '[', ']' -> "squareBracket"
        '!' -> "typeOperator"
        '=' -> "definitionOperator"
        '|', '&' -> "operator"
        ',' -> {
            state.expectType = false
            "separator"
        }

        '.' -> "punctuation"
        else -> null
    }

    /** The next character that is not a space, without consuming anything. */
    private fun nextNonSpace(stream: StringStream): Char? {
        var i = stream.pos
        while (i < stream.string.length && stream.string[i].isWhitespace()) i++
        return stream.string.getOrNull(i)
    }

    private fun isNameStart(c: Char) = c.isLetter() || c == '_'

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'
}

/** The words GraphQL has before it knows anything about a schema. */
val GRAPHQL_KEYWORDS = listOf(
    "query", "mutation", "subscription", "fragment", "on",
    "type", "schema", "scalar", "interface", "union", "enum", "input",
    "extend", "implements", "directive", "repeatable",
)

private val GRAPHQL_LITERALS = setOf("true", "false", "null")

/** Built-in scalars, worth completing even with no schema to hand. */
private val GRAPHQL_BUILTIN_TYPES = listOf("String", "Int", "Float", "Boolean", "ID")

/**
 * Completion for GraphQL: the language's own words, plus what this document has
 * already said.
 *
 * There is no schema here and no introspection, so the document is the only
 * source of field names — which covers the case that actually recurs, typing a
 * field you have already typed once. A list that looked schema-aware and was not
 * would be worse than one that is plainly a word list.
 */
val graphQlCompletions: CompletionSource = { context ->
    val match = context.matchBefore(Regex("[A-Za-z_][A-Za-z0-9_]*$"))
    when {
        match == null -> null
        // Nothing is offered on an empty prefix unless it was asked for, or the
        // popup would open on every brace.
        match.text.isEmpty() && !context.explicit -> null
        else -> {
            val document = Regex("[A-Za-z_][A-Za-z0-9_]*")
                .findAll(context.state.doc.toString())
                .map { it.value }
                .distinct()
                .toList()

            CompletionResult(
                from = match.from,
                options = (GRAPHQL_KEYWORDS + GRAPHQL_BUILTIN_TYPES + document)
                    .distinct()
                    .map { word ->
                        Completion(
                            label = word,
                            type = when (word) {
                                in GRAPHQL_KEYWORDS -> "keyword"
                                in GRAPHQL_BUILTIN_TYPES -> "type"
                                else -> "variable"
                            },
                            // Keywords first: they are the words you cannot get
                            // from the document, so they are the ones worth
                            // offering before it.
                            boost = if (word in GRAPHQL_KEYWORDS) 1 else 0,
                        )
                    },
                validFor = Regex("[A-Za-z_][A-Za-z0-9_]*"),
            )
        }
    }
}

/** GraphQL highlighting and completion, as one extension. */
fun graphql(): LanguageSupport = LanguageSupport(
    language = StreamLanguage.define(graphQlParser),
    support = autocompletion(CompletionConfig(override = listOf(graphQlCompletions))),
)
