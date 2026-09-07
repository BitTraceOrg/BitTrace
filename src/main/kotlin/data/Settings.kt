package org.bittrace.data

import kotlinx.serialization.Serializable

/**
 * Persisted user settings. New fields must have defaults so older settings
 * files still deserialize (unknown keys are ignored on read).
 */
@Serializable
data class Settings(
    /** Port the sidecar proxy listens on. */
    val proxyPort: Int = 8888,
    /** Height of the inspector pane, in dp. */
    val inspectorHeightDp: Float = 320f,
    /** Width of the inspector in the horizontal layout, in dp. */
    val inspectorWidthDp: Float = 520f,
    /**
     * Which layout the app uses, as stored: "right" is the horizontal layout
     * (panes side by side — the inspector beside the table, request above
     * response) and "bottom" the vertical one (panes stacked — the inspector
     * under the table, request and response side by side).
     *
     * The stored words are the old ones on purpose. Renaming a persisted value
     * would silently reset every install that already has a preference, and the
     * only thing gained would be that a file nobody reads matches a label that
     * everybody does. [Settings.horizontalLayout] is the translation, and the
     * one place either word appears.
     */
    val inspectorDock: String = "bottom",
    /** Request/response split fraction (0..1) along the inspector's long axis. */
    val requestResponseSplit: Float = 0.5f,
    /** Active theme id, contributed by a theme plugin (e.g. "precision-dark").
     *  Legacy "dark"/"light" values are migrated by the ThemeManager. */
    val theme: String = "precision-dark",
    /**
     * Flow-table columns to show, by key. Columns are opt-in: anything not
     * listed is hidden. Empty (the default) means "the table's default set" —
     * kept out of the settings file so the defaults can gain a column without
     * every existing install pinning the old list.
     */
    val tableColumns: List<String> = emptyList(),
    /**
     * Who commits, for API-client projects.
     *
     * Blank falls back to the machine's own `~/.gitconfig`; blank in both places
     * refuses the commit rather than inventing an identity, because a history
     * attributed to `bittrace@localhost` is worse than one that would not start.
     */
    val gitAuthorName: String = "",
    val gitAuthorEmail: String = "",
    /**
     * A personal access token for HTTPS remotes, keyed by nothing — one token
     * covers every host you push to.
     *
     * Stored here in plain text, like everything else in this file, so scope it
     * to the repositories you actually push. It is never committed: settings
     * live beside the collections folder, not inside it.
     */
    val gitToken: String = "",
    /** Width of the API client's collections tree, in dp. */
    val apiTreeWidthDp: Float = 240f,
    /** Width of the API client's response pane when the layout is horizontal, in dp. */
    val apiResponseWidthDp: Float = 520f,
    /** Height of the API client's response pane when the layout is vertical, in dp. */
    val apiResponseHeightDp: Float = 320f,

    // --- API client defaults -------------------------------------------------
    //
    // What a request is sent with when it does not say otherwise. Every value
    // below is what the app did before any of this was settable, so turning the
    // feature on changes nothing until somebody changes something — see
    // `org.bittrace.api.RequestSettings` for how a request overrides one.

    /** Deadline for the whole exchange, in milliseconds. */
    val apiTimeoutMs: Long = 30_000,
    /** `auto`, `http/1.1` or `http/2`; auto lets the JDK negotiate. */
    val apiHttpVersion: String = "auto",
    /**
     * Off, deliberately: an API client should show what the endpoint answered
     * rather than silently following it somewhere else. On, each hop still goes
     * through the proxy and is captured as its own flow.
     */
    val apiFollowRedirects: Boolean = false,
    val apiMaxRedirects: Int = 5,
    /** `whatwg`, `rfc3986` or `none` — how query parameters are escaped. */
    val apiUrlEncoding: String = "whatwg",
)

/**
 * Whether the layout is the horizontal one — panes side by side, with the
 * inspector beside what it inspects rather than under it.
 *
 * [Settings.inspectorDock] stays a "right"/"bottom" string so settings files
 * written by earlier builds still load; this is the one place that knows it.
 */
val Settings.horizontalLayout: Boolean
    get() = inspectorDock.equals("right", ignoreCase = true)
