package org.bittrace.ui.layouts.forge.components

import org.bittrace.ui.components.FormLabel
import org.bittrace.ui.components.FormNote
import org.bittrace.ui.components.PaneHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import org.bittrace.api.ApiClientState
import org.bittrace.api.HTTP_VERSIONS
import org.bittrace.api.RequestSettings
import org.bittrace.api.URL_ENCODINGS
import org.bittrace.api.httpVersionLabel
import org.bittrace.api.resolve
import org.bittrace.api.urlEncodingLabel
import org.bittrace.data.Settings
import org.bittrace.ui.components.CheckBox
import org.bittrace.ui.P
import org.bittrace.ui.components.PzText
import org.bittrace.ui.components.Segment
import org.bittrace.ui.components.SegmentedToggle
import org.bittrace.ui.components.TextInput
import org.bittrace.ui.Typo
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The Settings tab: how this one request is sent.
 *
 * Every row starts inherited — showing the app default, dimmed, tagged
 * `default` — and becomes an override the moment you touch it. The tag is not
 * decoration: without it a row showing `30000` cannot be told from a row that
 * was *set* to 30000, and the difference decides whether changing the default
 * later moves this request or leaves it behind.
 *
 * The reset arrow is how a row goes back, and it appears only on rows that have
 * somewhere to go back to.
 */
@Composable
fun RequestSettingsTab(state: ApiClientState, defaults: Settings) {
    val settings = state.request.settings
    val effective = settings.resolve(defaults)

    fun edit(change: (RequestSettings) -> RequestSettings) =
        state.edit { it.copy(settings = change(it.settings)) }

    // P.input, not P.bg: a light theme puts content on the window surface but a
    // form on white, and this pane is a form. In a dark theme the two coincide,
    // so nothing moves there.
    Column(Modifier.fillMaxSize().background(P.input).verticalScroll(rememberScrollState())) {
        Group("Connection", first = true)

        SettingRow(
            label = "HTTP version",
            overridden = settings.httpVersion != null,
            onReset = { edit { it.copy(httpVersion = null) } },
        ) {
            SegmentedToggle(
                segments = HTTP_VERSIONS.map { Segment(it, httpVersionLabel(it)) },
                selected = effective.httpVersion,
            ) { picked -> edit { it.copy(httpVersion = picked) } }
        }
        FormNote(inset = true, text = "Auto lets the JDK negotiate. Naming HTTP/2 makes it an upgrade the server has to accept.")

        SettingRow(
            label = "Timeout",
            overridden = settings.timeoutMs != null,
            onReset = { edit { it.copy(timeoutMs = null) } },
        ) {
            NumberInput(effective.timeoutMs.toString()) { typed ->
                edit { it.copy(timeoutMs = typed?.toLongOrNull()) }
            }
            Spacer(Modifier.width(6.dp))
            PzText("ms", color = P.faint, style = Typo.caption, family = P.Ui)
        }
        FormNote(inset = true, text = "The deadline for the whole exchange. Connecting has its own fixed 15 s limit.")

        Group("Redirects")

        SettingRow(
            label = "Follow",
            overridden = settings.followRedirects != null,
            onReset = { edit { it.copy(followRedirects = null) } },
        ) {
            CheckBox(effective.followRedirects) { on -> edit { it.copy(followRedirects = on) } }
        }
        FormNote(inset = true, text = "Off, the response you see is the redirect itself. On, each hop is still captured as its own flow.")

        SettingRow(
            label = "Maximum",
            overridden = settings.maxRedirects != null,
            enabled = effective.followRedirects,
            onReset = { edit { it.copy(maxRedirects = null) } },
        ) {
            NumberInput(
                value = effective.maxRedirects.toString(),
                enabled = effective.followRedirects,
            ) { typed -> edit { it.copy(maxRedirects = typed?.toIntOrNull()) } }
        }
        FormNote(inset = true, text = "Reaching the limit returns the last response rather than an error — a redirect loop is a finding.")

        Group("URL")

        SettingRow(
            label = "Query encoding",
            overridden = settings.urlEncoding != null,
            onReset = { edit { it.copy(urlEncoding = null) } },
        ) {
            SegmentedToggle(
                segments = URL_ENCODINGS.map { Segment(it, urlEncodingLabel(it)) },
                selected = effective.urlEncoding,
            ) { picked -> edit { it.copy(urlEncoding = picked) } }
        }
        FormNote(inset = true, text = 
            "Applies to the params table, not to the URL you typed — that is sent as written. " +
                "WHATWG sends a space as +, RFC 3986 as %20, None sends both verbatim.",
        )
    }
}

/**
 * A section heading, drawn as the same strip every other table header uses.
 *
 * Ruled top and bottom so the band reads as a divider between groups rather than
 * as a caption belonging to the rows under it — except at the very top, where
 * the tab strip above already draws a rule and a second one would sit on it. A
 * heading owns the rule above itself, so no two rules can ever land on the same
 * pixel line.
 */
@Composable
private fun Group(title: String, first: Boolean = false) =
    PaneHeader(title = title, topRule = !first)

/**
 * One setting: its name, its control, and whether it is this request's or the
 * app's.
 */
@Composable
private fun SettingRow(
    label: String,
    overridden: Boolean,
    enabled: Boolean = true,
    onReset: () -> Unit,
    control: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormLabel(label, enabled)
        // Dimmed while inherited, so a glance down the tab says which rows this
        // request has an opinion about.
        Box(Modifier.alpha(if (overridden) 1f else INHERITED_ALPHA)) {
            Row(verticalAlignment = Alignment.CenterVertically) { control() }
        }
        Spacer(Modifier.width(8.dp))
        if (overridden) {
            IconActionButton(
                key = AllIconsKeys.General.Reset,
                contentDescription = "Use the default for $label",
                onClick = onReset,
            )
        } else {
            PzText("default", color = P.faint, style = Typo.micro, family = P.Ui)
        }
    }
}

/**
 * A numeric field.
 *
 * Blanking it clears the override rather than sending zero — an empty box means
 * "I have no opinion", which is exactly what inheriting is.
 */
@Composable
private fun NumberInput(value: String, enabled: Boolean = true, onChange: (String?) -> Unit) {
    TextInput(
        value = value,
        onValueChange = { typed ->
            if (typed.all { it.isDigit() }) onChange(typed.takeIf { it.isNotBlank() })
        },
        enabled = enabled,
        modifier = Modifier.width(90.dp),
    )
}

/**
 * The label column, and the gap after it.
 *
 * The width has to fit the longest single *word* a label uses: a fixed column
 * narrower than a word does not wrap the label, it breaks the word. The gap is
 * separate from the width so a label that fills the column still cannot touch
 * the control beside it.
 */

/** Enough to read as secondary without becoming unreadable. */
private const val INHERITED_ALPHA = 0.55f
