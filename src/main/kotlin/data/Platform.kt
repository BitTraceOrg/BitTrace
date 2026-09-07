package org.bittrace.data

/**
 * Which OS this is, asked once.
 *
 * Three places had each written their own answer: `startsWith("win")` on a
 * lowercased name, `startsWith("Windows", ignoreCase = true)`, and
 * `contains("win")` — the last of which also matches Darwin's "Windows"-free
 * name only by luck, and would have matched a hypothetical "Darwin" build of
 * anything containing those letters. They agree today; nothing made them.
 *
 * The property is read once at class load because `os.name` cannot change while
 * the process runs, and a test that wants to pretend otherwise should be
 * exercising the branch it cares about directly.
 */
object Platform {

    private val name: String = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows: Boolean = name.startsWith("win")

    /** Both spellings: the JDK reports "Mac OS X", some JVMs report "Darwin". */
    val isMac: Boolean = name.contains("mac") || name.contains("darwin")
}
