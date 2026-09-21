package org.bittrace

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The folder an imported repository lands in is named from its URL, and the
 * shapes people paste are not one shape: HTTPS with and without `.git`, SSH
 * with a colon instead of a slash, and a trailing slash from a browser bar.
 */
class RepoNameTest {

    @Test
    fun `https url, with and without the git suffix`() {
        assertEquals("payments-api", repoNameOf("https://github.com/you/payments-api.git"))
        assertEquals("payments-api", repoNameOf("https://github.com/you/payments-api"))
    }

    @Test
    fun `a trailing slash is not a name`() {
        assertEquals("payments-api", repoNameOf("https://github.com/you/payments-api/"))
    }

    @Test
    fun `scp-style ssh url, where the colon does the slash's job`() {
        assertEquals("payments-api", repoNameOf("git@github.com:you/payments-api.git"))
        assertEquals("payments-api", repoNameOf("git@github.com:payments-api.git"))
    }

    @Test
    fun `nothing usable still yields a name the tree can show`() {
        assertEquals("Imported project", repoNameOf(""))
        assertEquals("Imported project", repoNameOf("   "))
        assertEquals("Imported project", repoNameOf("https://github.com/you/.git"))
    }
}
