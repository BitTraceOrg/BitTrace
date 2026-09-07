package ui.components.editor

import org.bittrace.ui.components.editor.languageFor
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * `languageFor` must hand back the *same* language every time.
 *
 * Not a micro-optimisation: `StreamLanguage.define` appends a node type to a
 * table in the language module that is process-global and never pruned, and the
 * entry retains the language that created it. Since the editor composables call
 * `languageFor` on every re-key — which is what paging Body → Raw → Hex on one
 * flow does — returning a fresh language per call grew the heap for as long as
 * anyone kept clicking. Measured before the cache: 200 calls, 200 entries.
 *
 * Identity is the property that prevents it, so identity is what is asserted.
 */
class LanguageForTest {

    @Test
    fun `same content type yields the same instance`() {
        assertSame(languageFor("application/json"), languageFor("application/json"))
        assertSame(languageFor("text/html"), languageFor("text/html"))
    }

    @Test
    fun `the plain-text fallback is shared too`() {
        // The leaking path: anything unrecognised, and the whole Hex tab.
        val a = languageFor("application/octet-stream")
        val b = languageFor("")
        val c = languageFor("application/x-www-form-urlencoded")
        assertSame(a, b)
        assertSame(b, c)
    }

    @Test
    fun `matching is loose, and charset parameters do not split the cache`() {
        // A content type in the wild is `application/vnd.api+json; charset=utf-8`
        // far more often than it is `application/json`.
        assertSame(languageFor("application/json"), languageFor("application/vnd.api+json; charset=utf-8"))
        assertSame(languageFor("text/html"), languageFor("TEXT/HTML; charset=UTF-8"))
    }

    @Test
    fun `different languages stay different`() {
        assertNotSame(languageFor("application/json"), languageFor("text/html"))
        assertNotSame(languageFor("application/json"), languageFor("application/octet-stream"))
        assertNotSame(languageFor("application/javascript"), languageFor("application/typescript"))
    }
}
