package org.bittrace.api

import java.nio.file.Files
import java.nio.file.Path
import org.bittrace.data.writeAtomically

/**
 * A project's own page of prose, kept beside its requests.
 *
 * Every project has one, written the first time the project is walked, because
 * a documentation file that has to be created before it can be written is a
 * documentation file nobody writes. The starter page is a shape to fill in
 * rather than a blank sheet, for the same reason.
 *
 * Plain Markdown in a plain `.md`, with no leading dot: unlike the variables
 * file this is meant to be seen, edited in whatever else you use, and read on
 * the forge of your choice once the project is pushed. `CollectionStore` only
 * ever takes a `.yaml` for a request, so a file at this level costs the walk
 * nothing.
 */
object ProjectDocs {

    /** Shouted, the way a repository's own README is, and for the same reason. */
    const val FILE_NAME = "DOCUMENTATION.md"

    fun pathIn(project: Path): Path = project.resolve(FILE_NAME)

    /**
     * The project's documentation, writing the starter page first if it has none.
     *
     * Never throws. An unreadable file costs you the page you are looking at,
     * not the project tab it is drawn in.
     */
    fun read(project: Path): String = runCatching {
        ensureIn(project)
        Files.readString(pathIn(project))
    }.getOrDefault(TEMPLATE)

    /**
     * Writes the starter page if, and only if, there is nothing there.
     *
     * Called from the walk, so it runs against every project on every reload:
     * the existence check is one stat per project, and the alternative — doing
     * it only at creation — leaves every project made before this existed
     * without a page.
     */
    fun ensureIn(project: Path) {
        val file = pathIn(project)
        if (Files.exists(file)) return
        runCatching { write(project, TEMPLATE) }
    }

    /**
     * Temp file plus move, so a failure part-way cannot truncate what is there.
     *
     * Written LF-only, like every other file this app writes: the `.gitattributes`
     * it generates says `* text eol=lf`, and a page saved with the platform's own
     * endings would show as changed from the moment it was checked out.
     */
    fun write(project: Path, text: String) {
        writeAtomically(pathIn(project), text.replace("\r\n", "\n"))
    }

    /**
     * What a new project's page says.
     *
     * It documents the editor it is read in, so what it advertises is what the
     * panel draws: CommonMark, plus GitHub tables, by way of Jewel's renderer.
     */
    val TEMPLATE: String = """
        # Documentation

        Welcome to your collection documentation! This page ships with every
        project, so there is somewhere to say what these endpoints are for
        before the next person has to guess. The next person is usually you, at
        3am, and you will not remember.

        ## Overview

        Use this section for the high-level view:

        * The purpose of these API endpoints
        * Key features and functionality
        * Who is expected to call them, and from where

        > It's dangerous to go alone! Take this.

        ## Getting started

        * Fill in the project's variables, then write `{{host}}` instead of a hostname
        * Open a request, send it, read the response
        * Write down whatever surprised you, here, while it is still surprising

        ## Best Practices

        * Keep this page up to date — stale documentation is worse than none
        * Include request and response examples
        * Document the error scenarios too; the no-win ones especially
        * Add relevant links and references
        * Explain the *why*. The request file already says the *what*

        ## Markdown Support

        This page is Markdown, so you can use:

        * **Bold** and *italic* text
        * `inline code`, and fenced blocks
        * Headings, nested lists and quotes
        * [Links](https://bittrace.dev)
        * Tables, for the things a list keeps getting wrong

        ```json
        { "answer": 42, "question": "unknown" }
        ```

        | Variable  | What it holds                        |
        |-----------|--------------------------------------|
        | `host`    | Where these requests are pointed      |
        | `token`   | What they carry to get past the door  |

        ## Notes

        Anything that fits nowhere else: rate limits, the endpoint that returns
        `200` with the failure in the body, the one field the docs upstream
        forgot. If you have a bad feeling about it, that is exactly the thing
        worth writing down.
        ---
        *Double-click this page to edit it. This is the way.*
    """.trimIndent() + "\n"
}
