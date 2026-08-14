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
    /** Request/response split fraction (0..1) of the inspector width. */
    val requestResponseSplit: Float = 0.5f,
    /** Active theme id, contributed by a theme plugin (e.g. "precision-dark").
     *  Legacy "dark"/"light" values are migrated by the ThemeManager. */
    val theme: String = "precision-dark",
)
