package org.bittrace.ui.components.editor

import org.bittrace.plugin.format.TokenKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenizerTest {

    /** `(text, kind)` for every token of a document, lexed line by line with state carried over. */
    private fun lex(contentType: String, text: String): List<Pair<String, TokenKind>> {
        val tokenizer = languageFor(contentType).tokenizer
        val out = mutableListOf<Pair<String, TokenKind>>()
        val run = TokenRun()
        var state = tokenizer.initialState
        for (line in text.split('\n')) {
            tokenizer.tokenize(line, 0, line.length, state, { s, e, k -> out += line.substring(s, e) to k }, run)
            state = tokenizer.lineEnd(run.state)
        }
        return out
    }

    private fun kindOf(tokens: List<Pair<String, TokenKind>>, text: String) = tokens.first { it.first == text }.second

    @Test
    fun json() {
        val t = lex("application/json", """{"key": "value", "n": -1.5e3, "ok": true, "x": null}""")
        assertEquals(TokenKind.PROPERTY, kindOf(t, "\"key\""))
        assertEquals(TokenKind.STRING, kindOf(t, "\"value\""))
        assertEquals(TokenKind.NUMBER, kindOf(t, "-1.5e3"))
        assertEquals(TokenKind.LITERAL, kindOf(t, "true"))
        assertEquals(TokenKind.LITERAL, kindOf(t, "null"))
        assertEquals(TokenKind.PUNCTUATION, kindOf(t, "{"))
    }

    @Test
    fun `javascript block comments and template strings span lines`() {
        val t = lex("application/javascript", "const a = `x\ny` /* one\ntwo */ obj.prop // c")
        assertEquals(TokenKind.KEYWORD, kindOf(t, "const"))
        assertEquals(TokenKind.STRING, kindOf(t, "`x"))
        assertEquals(TokenKind.STRING, kindOf(t, "y`"))
        assertEquals(TokenKind.COMMENT, kindOf(t, "/* one"))
        assertEquals(TokenKind.COMMENT, kindOf(t, "two */"))
        assertEquals(TokenKind.PROPERTY, kindOf(t, "prop"))
        assertEquals(TokenKind.COMMENT, kindOf(t, "// c"))
    }

    @Test
    fun `typescript has its own keywords`() {
        val t = lex("application/typescript", "interface Foo { a: string }")
        assertEquals(TokenKind.KEYWORD, kindOf(t, "interface"))
        assertEquals(TokenKind.TYPE, kindOf(t, "Foo"))
        val js = lex("application/javascript", "interface Foo")
        assertTrue(js.none { it.first == "interface" && it.second == TokenKind.KEYWORD })
    }

    @Test
    fun `css tells properties from values, and nests at-rules`() {
        val t = lex("text/css", "@media (min-width: 600px) {\n  .card { color: red; }\n}")
        assertEquals(TokenKind.KEYWORD, kindOf(t, "@media"))
        assertEquals(TokenKind.ATTRIBUTE, kindOf(t, ".card"), "inside an at-rule, .card is still a selector")
        assertEquals(TokenKind.PROPERTY, kindOf(t, "color"))
        assertEquals(TokenKind.LITERAL, kindOf(t, "red"))
        assertEquals(TokenKind.NUMBER, kindOf(t, "600px"))
    }

    @Test
    fun `markup across lines`() {
        val t = lex("application/xml", "<a href=\"x\"\n   b='y'>text &amp; <!-- c\n d --></a>")
        assertEquals(TokenKind.TAG, kindOf(t, "a"))
        assertEquals(TokenKind.ATTRIBUTE, kindOf(t, "href"))
        assertEquals(TokenKind.ATTRIBUTE, kindOf(t, "b"), "attributes on the next line are still attributes")
        assertEquals(TokenKind.STRING, kindOf(t, "'y'"))
        assertEquals(TokenKind.META, kindOf(t, "&amp;"))
        assertEquals(TokenKind.COMMENT, kindOf(t, "<!-- c"))
        assertEquals(TokenKind.COMMENT, kindOf(t, " d -->"))
    }

    @Test
    fun `html script bodies are not tags`() {
        val t = lex("text/html", "<script>if (a < b) {}</script><p>")
        assertTrue(t.none { it.second == TokenKind.TAG && it.first == "b" })
        assertEquals(listOf("script", "script", "p"), t.filter { it.second == TokenKind.TAG }.map { it.first })
    }

    @Test
    fun `graphql names are typed by context`() {
        val t = lex("application/graphql", "query Q(\$id: ID!) {\n  user(id: \$id) { name ...on Admin { level } }\n}")
        assertEquals(TokenKind.KEYWORD, kindOf(t, "query"))
        assertEquals(TokenKind.VARIABLE, kindOf(t, "\$id"))
        assertEquals(TokenKind.TYPE, kindOf(t, "ID"))
        assertEquals(TokenKind.ATTRIBUTE, kindOf(t, "id"))
        assertEquals(TokenKind.TYPE, kindOf(t, "Admin"))
    }

    @Test
    fun `plain text has no tokens`() {
        assertTrue(lex("application/octet-stream", "{\"a\": 1}").isEmpty())
    }

    /**
     * The property long lines rely on: stopping anywhere the tokenizer chose
     * and resuming from the state it left produces the same tokens as one
     * uninterrupted run.
     */
    @Test
    fun `resuming at any stop gives the same tokens as one run`() {
        val samples = mapOf(
            "application/json" to """{"a": [1, 2, {"b": "c \" d"}], "e": true, "f": null}""".repeat(40),
            "application/javascript" to "let x = 'a' + `b` /* c */ + obj.y(1, 2); // tail ".repeat(40),
            "text/css" to "@media screen { .a:hover { color: #fff; margin: 0 2px; } } ".repeat(40),
            "text/html" to "<div class=\"x\"><script>a<b</script><!-- c --><br/></div>".repeat(40),
            "application/graphql" to "query Q(\$a: Int) { f(a: \$a) { ... on T { g } } } # c\n".repeat(1).replace("\n", " ").repeat(40),
        )
        val random = Random(7)
        for ((type, line) in samples) {
            val tokenizer = languageFor(type).tokenizer
            val run = TokenRun()
            val whole = mutableListOf<Triple<Int, Int, TokenKind>>()
            tokenizer.tokenize(line, 0, line.length, tokenizer.initialState, { s, e, k -> whole += Triple(s, e, k) }, run)

            val pieces = mutableListOf<Triple<Int, Int, TokenKind>>()
            var pos = 0
            var state = tokenizer.initialState
            while (pos < line.length) {
                tokenizer.tokenize(line, pos, pos + 1 + random.nextInt(40), state, { s, e, k -> pieces += Triple(s, e, k) }, run)
                assertTrue(run.pos > pos, "$type: a run always makes progress")
                pos = run.pos
                state = run.state
            }
            assertEquals(whole, pieces, type)
        }
    }
}
