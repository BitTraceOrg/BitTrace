package org.bittrace.ui.components.editor

import org.bittrace.plugin.builtin.jsKeywords
import org.bittrace.plugin.format.TokenKind

/**
 * Text with nothing to say about itself — the Hex tab, a body no formatter
 * claims, and any view whose point is that nothing was done to what it shows.
 */
object PlainTokenizer : Tokenizer {
    override fun tokenize(line: String, start: Int, minEnd: Int, state: Int, sink: TokenSink?, run: TokenRun) {
        run.pos = line.length
        run.state = state
    }
}

/** JSON. Stateless across lines: nothing in JSON spans one. */
object JsonTokenizer : Tokenizer {
    override fun tokenize(line: String, start: Int, minEnd: Int, state: Int, sink: TokenSink?, run: TokenRun) {
        var i = start
        val n = line.length
        while (i < n && (i < minEnd || i == start)) {
            val c = line[i]
            when {
                c == '"' -> {
                    val end = quotedEnd(line, i)
                    // A string followed by ':' is an object key.
                    val next = nextNonSpace(line, end)
                    sink.emit(i, end, if (next >= 0 && line[next] == ':') TokenKind.PROPERTY else TokenKind.STRING)
                    i = end
                }

                c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit()) -> {
                    val end = numberEnd(line, i)
                    sink.emit(i, end, TokenKind.NUMBER)
                    i = end
                }

                c.isLetter() -> {
                    val end = wordEnd(line, i)
                    if (isJsonLiteral(line, i, end)) sink.emit(i, end, TokenKind.LITERAL)
                    i = end
                }

                c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':' -> {
                    sink.emit(i, i + 1, TokenKind.PUNCTUATION)
                    i++
                }

                else -> i++
            }
        }
        run.pos = i
        run.state = 0
    }

    private fun isJsonLiteral(line: String, start: Int, end: Int): Boolean {
        val len = end - start
        return (len == 4 && (line.startsWith("true", start) || line.startsWith("null", start))) ||
            (len == 5 && line.startsWith("false", start))
    }
}

/**
 * JavaScript and TypeScript.
 *
 * Two constructs outlive a line — block comments and template strings — and
 * the state is which of the two, if either, the line starts inside.
 */
class ScriptTokenizer(private val keywords: Set<String>) : Tokenizer {

    override fun tokenize(line: String, start: Int, minEnd: Int, state: Int, sink: TokenSink?, run: TokenRun) {
        var i = start
        var s = state
        val n = line.length
        while (i < n && (i < minEnd || i == start)) {
            if (s == IN_COMMENT) {
                val close = line.indexOf("*/", i)
                val end = if (close < 0) n else close + 2
                sink.emit(i, end, TokenKind.COMMENT)
                if (close >= 0) s = NORMAL
                i = end
                continue
            }
            if (s == IN_TEMPLATE) {
                val close = templateClose(line, i)
                val end = if (close < 0) n else close + 1
                sink.emit(i, end, TokenKind.STRING)
                if (close >= 0) s = NORMAL
                i = end
                continue
            }
            val c = line[i]
            when {
                c == '/' && i + 1 < n && line[i + 1] == '/' -> {
                    sink.emit(i, n, TokenKind.COMMENT)
                    i = n
                }

                c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                    val close = line.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    sink.emit(i, end, TokenKind.COMMENT)
                    if (close < 0) s = IN_COMMENT
                    i = end
                }

                c == '`' -> {
                    val close = templateClose(line, i + 1)
                    val end = if (close < 0) n else close + 1
                    sink.emit(i, end, TokenKind.STRING)
                    if (close < 0) s = IN_TEMPLATE
                    i = end
                }

                c == '"' || c == '\'' -> {
                    val end = quotedEnd(line, i)
                    sink.emit(i, end, TokenKind.STRING)
                    i = end
                }

                c.isDigit() -> {
                    val end = numberEnd(line, i)
                    sink.emit(i, end, TokenKind.NUMBER)
                    i = end
                }

                c.isLetter() || c == '_' || c == '$' -> {
                    val end = wordEnd(line, i)
                    if (sink != null) {
                        val word = line.substring(i, end)
                        val member = i > 0 && line[i - 1] == '.'
                        val kind = when {
                            word in JS_LITERALS -> TokenKind.LITERAL
                            !member && word in keywords -> TokenKind.KEYWORD
                            member -> TokenKind.PROPERTY
                            // `Foo` in `new Foo()` / `x: Foo` — a useful signal in TS especially.
                            word[0].isUpperCase() -> TokenKind.TYPE
                            else -> TokenKind.PLAIN
                        }
                        sink.emit(i, end, kind)
                    }
                    i = end
                }

                c in SCRIPT_PUNCT -> {
                    sink.emit(i, i + 1, TokenKind.PUNCTUATION)
                    i++
                }

                else -> i++
            }
        }
        run.pos = i
        run.state = s
    }

    /** The closing backtick at or after [from], or -1. */
    private fun templateClose(line: String, from: Int): Int {
        var j = from
        while (j < line.length) {
            when (line[j]) {
                '\\' -> j += 2
                '`' -> return j
                else -> j++
            }
        }
        return -1
    }

    private companion object {
        const val NORMAL = 0
        const val IN_COMMENT = 1
        const val IN_TEMPLATE = 2
        val JS_LITERALS = setOf("true", "false", "null", "undefined", "NaN", "Infinity")
    }
}

internal fun scriptTokenizer(typescript: Boolean) = ScriptTokenizer(jsKeywords(typescript))

/**
 * CSS, classified by where a token sits rather than by what it is.
 *
 * The same rules as the formatter's lexer: the same word is a property before
 * the colon and a value after it, and `@media { .card { … } }` holds rules
 * rather than declarations. All of that is packed into the state — whether the
 * line starts in a comment, in a declaration block, in an at-rule prelude or
 * past a colon, and a stack of which kind each enclosing block is.
 */
object CssTokenizer : Tokenizer {
    private const val COMMENT = 1
    private const val DECLARATIONS = 2
    private const val AT_RULE = 4
    private const val IN_VALUE = 8
    private const val DEPTH_SHIFT = 4
    private const val DEPTH_MASK = 0x1F
    private const val STACK_SHIFT = 9
    private const val STACK_BITS = 22

    override fun tokenize(line: String, start: Int, minEnd: Int, state: Int, sink: TokenSink?, run: TokenRun) {
        var i = start
        var s = state
        val n = line.length
        while (i < n && (i < minEnd || i == start)) {
            if (s and COMMENT != 0) {
                val close = line.indexOf("*/", i)
                val end = if (close < 0) n else close + 2
                sink.emit(i, end, TokenKind.COMMENT)
                if (close >= 0) s = s and COMMENT.inv()
                i = end
                continue
            }
            val declarations = s and DECLARATIONS != 0
            val inValue = s and IN_VALUE != 0
            val c = line[i]
            when {
                // CSS has no line comments — `//` is a syntax error, not a comment.
                c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                    val close = line.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    sink.emit(i, end, TokenKind.COMMENT)
                    if (close < 0) s = s or COMMENT
                    i = end
                }

                c == '"' || c == '\'' -> {
                    val end = quotedEnd(line, i)
                    sink.emit(i, end, TokenKind.STRING)
                    i = end
                }

                // A hex colour in a value, an id in a selector.
                c == '#' -> {
                    val end = wordEnd(line, i + 1, extra = "-_")
                    sink.emit(i, end, if (inValue) TokenKind.NUMBER else TokenKind.ATTRIBUTE)
                    i = end
                }

                c == '@' -> {
                    s = s or AT_RULE
                    val end = wordEnd(line, i + 1, extra = "-_")
                    sink.emit(i, end, TokenKind.KEYWORD)
                    i = end
                }

                c == '.' && i + 1 < n && line[i + 1].isLetter() && !declarations -> {
                    val end = wordEnd(line, i + 1, extra = "-_")
                    sink.emit(i, end, TokenKind.ATTRIBUTE)
                    i = end
                }

                c == ':' && !declarations && i + 1 < n && line[i + 1].isLetter() -> {
                    val end = wordEnd(line, i + 1, extra = "-_:")
                    sink.emit(i, end, TokenKind.META)
                    i = end
                }

                c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit()) -> {
                    var end = numberEnd(line, i)
                    end = wordEnd(line, end, extra = "%")
                    sink.emit(i, end, TokenKind.NUMBER)
                    i = end
                }

                c.isLetter() || c == '-' || c == '_' -> {
                    val end = wordEnd(line, i, extra = "-_")
                    val kind = when {
                        !declarations -> TokenKind.TAG
                        inValue -> TokenKind.LITERAL
                        else -> TokenKind.PROPERTY
                    }
                    sink.emit(i, end, kind)
                    i = end
                }

                else -> {
                    when (c) {
                        '{' -> s = push(s)
                        '}' -> s = pop(s)
                        ':' -> s = s or IN_VALUE
                        ';' -> s = s and (IN_VALUE or AT_RULE).inv()
                    }
                    if (c in CSS_PUNCT) sink.emit(i, i + 1, TokenKind.PUNCTUATION)
                    i++
                }
            }
        }
        run.pos = i
        run.state = s
    }

    private fun push(state: Int): Int {
        val depth = (state ushr DEPTH_SHIFT) and DEPTH_MASK
        var s = state
        if (depth < STACK_BITS) {
            val bit = 1 shl (STACK_SHIFT + depth)
            s = if (s and DECLARATIONS != 0) s or bit else s and bit.inv()
        }
        val newDepth = minOf(depth + 1, DEPTH_MASK)
        s = (s and (DEPTH_MASK shl DEPTH_SHIFT).inv()) or (newDepth shl DEPTH_SHIFT)
        // A block opened by an at-rule holds rules; any other holds declarations.
        s = if (s and AT_RULE != 0) s and DECLARATIONS.inv() else s or DECLARATIONS
        return s and (AT_RULE or IN_VALUE).inv()
    }

    private fun pop(state: Int): Int {
        val depth = (state ushr DEPTH_SHIFT) and DEPTH_MASK
        if (depth == 0) return state and (DECLARATIONS or IN_VALUE).inv()
        val newDepth = depth - 1
        var s = (state and (DEPTH_MASK shl DEPTH_SHIFT).inv()) or (newDepth shl DEPTH_SHIFT)
        val enclosing = newDepth < STACK_BITS && (state and (1 shl (STACK_SHIFT + newDepth))) != 0
        s = if (enclosing) s or DECLARATIONS else s and DECLARATIONS.inv()
        if (newDepth < STACK_BITS) s = s and (1 shl (STACK_SHIFT + newDepth)).inv()
        return s and IN_VALUE.inv()
    }

    private const val CSS_PUNCT = "{}();:,>+~*=[]"
}

/**
 * XML and HTML markup.
 *
 * The state is the construct a line starts inside: text, a comment, CDATA, a
 * processing instruction or doctype, a tag's attribute list (attributes on
 * lines of their own are the common case in XML), a quoted attribute value —
 * and, for HTML, the raw text of a `<script>` or `<style>`, where a `<` is an
 * operator rather than a tag.
 */
class MarkupTokenizer(private val html: Boolean) : Tokenizer {

    override fun tokenize(line: String, start: Int, minEnd: Int, state: Int, sink: TokenSink?, run: TokenRun) {
        var i = start
        var mode = state and MODE_MASK
        var raw = state and RAW_MASK
        val n = line.length
        while (i < n && (i < minEnd || i == start)) {
            when (mode) {
                COMMENT, CDATA, PI, DECL -> {
                    val terminator = when (mode) {
                        COMMENT -> "-->"
                        CDATA -> "]]>"
                        PI -> "?>"
                        else -> ">"
                    }
                    val close = line.indexOf(terminator, i)
                    val end = if (close < 0) n else close + terminator.length
                    sink.emit(i, end, if (mode == COMMENT) TokenKind.COMMENT else TokenKind.META)
                    if (close >= 0) mode = TEXT
                    i = end
                }

                ATTR_DQ, ATTR_SQ -> {
                    val close = line.indexOf(if (mode == ATTR_DQ) '"' else '\'', i)
                    val end = if (close < 0) n else close + 1
                    sink.emit(i, end, TokenKind.STRING)
                    if (close >= 0) mode = TAG
                    i = end
                }

                RAW -> {
                    val name = if (raw == RAW_SCRIPT) "</script" else "</style"
                    val close = line.indexOf(name, i, ignoreCase = true)
                    if (close < 0) {
                        i = n
                    } else {
                        i = close
                        mode = TEXT
                        raw = 0
                    }
                }

                TAG -> {
                    val c = line[i]
                    when {
                        c.isWhitespace() -> i++
                        c == '"' || c == '\'' -> {
                            val close = line.indexOf(c, i + 1)
                            val end = if (close < 0) n else close + 1
                            sink.emit(i, end, TokenKind.STRING)
                            if (close < 0) mode = if (c == '"') ATTR_DQ else ATTR_SQ
                            i = end
                        }

                        c == '>' -> {
                            sink.emit(i, i + 1, TokenKind.PUNCTUATION)
                            mode = if (raw != 0) RAW else TEXT
                            i++
                        }

                        c == '/' -> {
                            // `/>` closes the tag outright, so no raw text follows it.
                            if (i + 1 < n && line[i + 1] == '>') raw = 0
                            sink.emit(i, i + 1, TokenKind.PUNCTUATION)
                            i++
                        }

                        c == '=' || c == '?' -> {
                            sink.emit(i, i + 1, TokenKind.PUNCTUATION)
                            i++
                        }

                        else -> {
                            val end = wordEnd(line, i, extra = ":-_.")
                            if (end > i) {
                                sink.emit(i, end, TokenKind.ATTRIBUTE)
                                i = end
                            } else {
                                i++
                            }
                        }
                    }
                }

                else -> { // TEXT
                    val c = line[i]
                    when {
                        c == '<' && line.startsWith("<!--", i) -> mode = COMMENT
                        c == '<' && line.startsWith("<![CDATA[", i) -> mode = CDATA
                        c == '<' && line.startsWith("<?", i) -> mode = PI
                        c == '<' && line.startsWith("<!", i) -> mode = DECL
                        c == '<' && i + 1 < n && (line[i + 1].isLetter() || line[i + 1] == '/' || line[i + 1] == '_') -> {
                            sink.emit(i, i + 1, TokenKind.PUNCTUATION)
                            var j = i + 1
                            val closing = line[j] == '/'
                            if (closing) {
                                sink.emit(j, j + 1, TokenKind.PUNCTUATION)
                                j++
                            }
                            val nameEnd = wordEnd(line, j, extra = ":-_.")
                            sink.emit(j, nameEnd, TokenKind.TAG)
                            raw = if (html && !closing) rawKind(line, j, nameEnd) else 0
                            mode = TAG
                            i = nameEnd
                        }

                        c == '&' -> {
                            val semi = line.indexOf(';', i)
                            if (semi in (i + 2)..(i + 12) && (i + 1 until semi).all { line[it].isLetterOrDigit() || line[it] == '#' }) {
                                sink.emit(i, semi + 1, TokenKind.META)
                                i = semi + 1
                            } else {
                                i++
                            }
                        }

                        else -> {
                            // Plain text up to the next thing that could start a token.
                            var j = i + 1
                            while (j < n && line[j] != '<' && line[j] != '&') j++
                            i = j
                        }
                    }
                }
            }
        }
        run.pos = i
        run.state = mode or raw
    }

    private fun rawKind(line: String, start: Int, end: Int): Int = when {
        end - start == 6 && line.regionMatches(start, "script", 0, 6, ignoreCase = true) -> RAW_SCRIPT
        end - start == 5 && line.regionMatches(start, "style", 0, 5, ignoreCase = true) -> RAW_STYLE
        else -> 0
    }

    private companion object {
        const val MODE_MASK = 0xF
        const val TEXT = 0
        const val COMMENT = 1
        const val CDATA = 2
        const val PI = 3
        const val DECL = 4
        const val TAG = 5
        const val ATTR_DQ = 6
        const val ATTR_SQ = 7
        const val RAW = 8
        const val RAW_MASK = 0x30
        const val RAW_SCRIPT = 0x10
        const val RAW_STYLE = 0x20
    }
}

/** The words GraphQL has before it knows anything about a schema. */
val GRAPHQL_KEYWORDS = listOf(
    "query", "mutation", "subscription", "fragment", "on",
    "type", "schema", "scalar", "interface", "union", "enum", "input",
    "extend", "implements", "directive", "repeatable",
)

/**
 * GraphQL.
 *
 * Its lexical grammar is small; what needs context is only which colour a name
 * takes. A name is a type when it follows `:` or `on`, an argument or alias
 * when a `:` follows it, and a field otherwise — wrong for about the same cases
 * GraphiQL's own highlighting is wrong for. Two bits of state carry it: that
 * and whether the line starts inside a `"""` block string.
 */
object GraphQlTokenizer : Tokenizer {
    private const val IN_BLOCK = 1
    private const val EXPECT_TYPE = 2
    private val keywords = GRAPHQL_KEYWORDS.toHashSet()

    override fun tokenize(line: String, start: Int, minEnd: Int, state: Int, sink: TokenSink?, run: TokenRun) {
        var i = start
        var s = state
        val n = line.length
        while (i < n && (i < minEnd || i == start)) {
            if (s and IN_BLOCK != 0) {
                val close = line.indexOf("\"\"\"", i)
                val end = if (close < 0) n else close + 3
                sink.emit(i, end, TokenKind.STRING)
                if (close >= 0) s = s and IN_BLOCK.inv()
                i = end
                continue
            }
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> {
                    sink.emit(i, n, TokenKind.COMMENT)
                    i = n
                }

                line.startsWith("\"\"\"", i) -> {
                    val close = line.indexOf("\"\"\"", i + 3)
                    val end = if (close < 0) n else close + 3
                    sink.emit(i, end, TokenKind.STRING)
                    if (close < 0) s = s or IN_BLOCK
                    i = end
                }

                c == '"' -> {
                    val end = quotedEnd(line, i)
                    sink.emit(i, end, TokenKind.STRING)
                    i = end
                }

                c == '$' || c == '@' -> {
                    val end = wordEnd(line, i + 1, extra = "_")
                    sink.emit(i, end, if (c == '$') TokenKind.VARIABLE else TokenKind.META)
                    i = end
                }

                c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit()) -> {
                    val end = numberEnd(line, i)
                    sink.emit(i, end, TokenKind.NUMBER)
                    i = end
                }

                c.isLetter() || c == '_' -> {
                    val end = wordEnd(line, i, extra = "_")
                    val word = line.substring(i, end)
                    val expecting = s and EXPECT_TYPE != 0
                    s = if (word == "on") s or EXPECT_TYPE else s and EXPECT_TYPE.inv()
                    val next = nextNonSpace(line, end)
                    val kind = when {
                        word == "true" || word == "false" || word == "null" -> TokenKind.LITERAL
                        word in keywords -> TokenKind.KEYWORD
                        expecting -> TokenKind.TYPE
                        next >= 0 && line[next] == ':' -> TokenKind.ATTRIBUTE
                        else -> TokenKind.PLAIN
                    }
                    sink.emit(i, end, kind)
                    i = end
                }

                c in "{}()[]!=|&,.:" -> {
                    when (c) {
                        ':' -> s = s or EXPECT_TYPE
                        '{', '}', '(', ')', ',' -> s = s and EXPECT_TYPE.inv()
                    }
                    sink.emit(i, i + 1, TokenKind.PUNCTUATION)
                    i++
                }

                else -> i++
            }
        }
        run.pos = i
        run.state = s
    }
}
