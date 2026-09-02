package org.bittrace.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.bittrace.api.AUTH_API_KEY
import org.bittrace.api.AUTH_BASIC
import org.bittrace.api.AUTH_BEARER
import org.bittrace.api.AUTH_NONE
import org.bittrace.api.AUTH_OAUTH1
import org.bittrace.api.AUTH_OAUTH2
import org.bittrace.api.GRANT_AUTH_CODE
import org.bittrace.api.GRANT_DEVICE_CODE
import org.bittrace.api.GRANT_JWT_BEARER
import org.bittrace.api.GRANT_PASSWORD
import org.bittrace.api.JWT_ALGORITHMS
import org.bittrace.api.JWT_RS256
import org.bittrace.api.GRANT_TYPES
import org.bittrace.api.SIGNATURE_METHODS
import org.bittrace.api.SIG_RSA_SHA1
import org.bittrace.api.grantLabel
import org.bittrace.api.oauth.OAuthService
import org.bittrace.api.oauth.redirectUri
import org.bittrace.ui.CheckBox
import org.bittrace.ui.Dropdown
import org.bittrace.ui.GhostButton
import org.bittrace.ui.PrimaryButton
import org.bittrace.ui.copyToClipboard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.IconActionButton
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.bittrace.api.AUTH_TYPES
import org.bittrace.api.ApiAuth
import org.bittrace.api.ApiClientState
import org.bittrace.api.KEY_IN_HEADER
import org.bittrace.api.KEY_IN_QUERY
import org.bittrace.api.authTypeLabel
import org.bittrace.ui.Format
import org.bittrace.ui.FormatPicker
import org.bittrace.ui.P
import org.bittrace.ui.PzText
import org.bittrace.ui.Segment
import org.bittrace.ui.SegmentedToggle
import org.bittrace.ui.TextInput
import org.bittrace.ui.Typo
import org.bittrace.ui.topBorder

/**
 * The Auth tab: pick a scheme, fill in its fields, see what gets sent.
 *
 * Switching scheme does not clear the fields of the one you left — [ApiAuth]
 * keeps them all — so comparing Basic against Bearer against an API key costs
 * nothing, which is most of the reason the panel exists.
 *
 * The strip along the bottom is not decoration. Auth never appears in the
 * Headers table (see [ApiAuth]), so it is the only place in the UI that shows
 * what the credential actually becomes on the wire — which matters most when a
 * field is blank, since a blank one is sent as blank rather than suppressed.
 */
@Composable
fun AuthTab(state: ApiClientState, oauth: OAuthService) {
    val auth = state.request.auth
    val scope = rememberCoroutineScope()
    // What an authorisation is doing right now: waiting on a browser, showing a
    // device code, or the error it ended with. Keyed to nothing — a new
    // authorisation replaces it, which is what the one status line means.
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun edit(change: (ApiAuth) -> ApiAuth) = state.edit { it.copy(auth = change(it.auth)) }

    Column(Modifier.fillMaxSize().background(P.input)) {
        // The same strip the Body tab picks a format with. Both are "one choice
        // out of a few, governing everything below it", which is what a
        // segmented control means — and a tab that changed its whole form from
        // a dropdown while its neighbour used buttons read as two designs.
        val types = remember(auth.type) { authTypesIncluding(auth.type) }
        FormatPicker(
            formats = types.map { Format(it, authTypeLabel(it)) },
            selected = auth.type,
            surface = P.input,
            onSelect = { picked -> edit { it.copy(type = picked.id) } },
        )

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (auth.type) {
                AUTH_BASIC -> {
                    Field("Username", auth.username) { v -> edit { it.copy(username = v) } }
                    Field("Password", auth.password) { v -> edit { it.copy(password = v) } }
                    Note(
                        "Sent as an Authorization header, base64 of user:password. " +
                            "That is encoding, not encryption — over plain HTTP anything in the path can read it.",
                    )
                }

                AUTH_BEARER -> {
                    Field("Scheme", auth.scheme, hint = "Bearer") { v -> edit { it.copy(scheme = v) } }
                    Field("Token", auth.token, hint = "paste the token") { v -> edit { it.copy(token = v) } }
                    Note(
                        "The scheme is the word before the token; leave it empty for a server that wants the " +
                            "token bare. Blank fields are sent blank, so you can see what a server makes of them.",
                    )
                }

                AUTH_API_KEY -> {
                    Field("Key", auth.keyName, hint = "X-Api-Key") { v -> edit { it.copy(keyName = v) } }
                    Field("Value", auth.keyValue) { v -> edit { it.copy(keyValue = v) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormLabel("Add to")
                        SegmentedToggle(
                            segments = listOf(Segment(KEY_IN_HEADER, "Header"), Segment(KEY_IN_QUERY, "Query")),
                            selected = auth.keyIn,
                        ) { picked -> edit { it.copy(keyIn = picked) } }
                    }
                    Note(
                        "A key sent in the query is appended to the URL as the request goes out. It stays out of " +
                            "the Params table, so it is never written into the saved request's URL.",
                    )
                }

                AUTH_OAUTH1 -> {
                    Field("Consumer key", auth.consumerKey) { v -> edit { it.copy(consumerKey = v) } }
                    Field("Consumer secret", auth.consumerSecret) { v -> edit { it.copy(consumerSecret = v) } }
                    Field("Token", auth.oauthToken) { v -> edit { it.copy(oauthToken = v) } }
                    Field("Token secret", auth.oauthTokenSecret) { v -> edit { it.copy(oauthTokenSecret = v) } }
                    Choice("Signature", SIGNATURE_METHODS, auth.signatureMethod) { v ->
                        edit { it.copy(signatureMethod = v) }
                    }
                    Field("Realm", auth.realm, hint = "optional") { v -> edit { it.copy(realm = v) } }
                    if (auth.signatureMethod == SIG_RSA_SHA1) {
                        Field("Private key", auth.privateKeyPath, hint = "PKCS#8 PEM") { v ->
                            edit { it.copy(privateKeyPath = v) }
                        }
                    }
                    Note(
                        "Every request is signed as it is sent — there is no token to fetch and nothing to " +
                            "expire. The signature covers the method, the URL, the query and a form body.",
                    )
                }

                AUTH_OAUTH2 -> OAuth2Form(auth, oauth, state, busy, status, ::edit) { newBusy, newStatus ->
                    busy = newBusy
                    status = newStatus
                }

                AUTH_NONE -> Note(
                    "This request sends no credentials. An Authorization header typed into the Headers tab still goes out.",
                )

                // A scheme written by a later build. The value is kept as it is,
                // so opening the request here and saving it does not erase it.
                else -> Note(
                    "This request uses \"${auth.type}\", which this build does not know how to send. " +
                        "Its settings are preserved; pick a scheme above to send something.",
                )
            }
        }

        Preview(auth)
    }
}

/**
 * The schemes to offer, including one this build does not know.
 *
 * A request saved by a later build names a scheme that is not in [AUTH_TYPES];
 * giving it a button of its own is what lets you see what the request is set to
 * — and lets you come back to it after looking at another — instead of the
 * strip silently showing nothing selected.
 */
private fun authTypesIncluding(type: String): List<String> =
    if (type in AUTH_TYPES) AUTH_TYPES else AUTH_TYPES + type

/**
 * The OAuth 2.0 form: the fields this grant uses, and the button that gets a
 * token.
 *
 * Only the fields the grant needs are shown. An Authorization Code form is not
 * a Client Credentials form, and rendering both while greying half is how a
 * panel with twelve boxes becomes one nobody reads.
 */
@Composable
private fun OAuth2Form(
    auth: ApiAuth,
    oauth: OAuthService,
    state: ApiClientState,
    busy: Boolean,
    status: String?,
    edit: ((ApiAuth) -> ApiAuth) -> Unit,
    onProgress: (Boolean, String?) -> Unit,
) {
    val token = state.tokens.of(auth)
    val interactive = auth.grantType == GRANT_AUTH_CODE || auth.grantType == GRANT_DEVICE_CODE

    Choice("Grant type", GRANT_TYPES, auth.grantType, ::grantLabel) { v -> edit { it.copy(grantType = v) } }

    Field("Client ID", auth.clientId) { v -> edit { it.copy(clientId = v) } }
    Field("Client secret", auth.clientSecret) { v -> edit { it.copy(clientSecret = v) } }
    CheckRow("Send credentials in the body", auth.credentialsInBody) { on ->
        edit { it.copy(credentialsInBody = on) }
    }
    Note("Off, they go in an Authorization header, which is what RFC 6749 says clients should prefer.")

    if (auth.grantType == GRANT_AUTH_CODE) {
        Field("Authorization URL", auth.authUrl) { v -> edit { it.copy(authUrl = v) } }
    }
    if (auth.grantType == GRANT_DEVICE_CODE) {
        Field("Device auth URL", auth.deviceAuthUrl) { v -> edit { it.copy(deviceAuthUrl = v) } }
    }
    Field("Token URL", auth.tokenUrl) { v -> edit { it.copy(tokenUrl = v) } }

    if (auth.grantType == GRANT_PASSWORD) {
        Field("Username", auth.username) { v -> edit { it.copy(username = v) } }
        Field("Password", auth.password) { v -> edit { it.copy(password = v) } }
    }

    if (auth.grantType == GRANT_JWT_BEARER) {
        Choice("Algorithm", JWT_ALGORITHMS, auth.jwtAlgorithm) { v -> edit { it.copy(jwtAlgorithm = v) } }
        Field("Issuer", auth.jwtIssuer, hint = "iss — the client") { v -> edit { it.copy(jwtIssuer = v) } }
        Field("Subject", auth.jwtSubject, hint = "sub — defaults to the issuer") { v ->
            edit { it.copy(jwtSubject = v) }
        }
        Field("JWT audience", auth.jwtAudience, hint = "aud — defaults to the token URL") { v ->
            edit { it.copy(jwtAudience = v) }
        }
        Field("Key ID", auth.jwtKeyId, hint = "kid — optional") { v -> edit { it.copy(jwtKeyId = v) } }
        Field("Valid for", auth.jwtValiditySeconds.toString(), hint = "seconds") { v ->
            v.toLongOrNull()?.let { seconds -> edit { it.copy(jwtValiditySeconds = seconds) } }
        }
        if (auth.jwtAlgorithm == JWT_RS256) {
            Field("Private key", auth.privateKeyPath, hint = "PKCS#8 PEM") { v ->
                edit { it.copy(privateKeyPath = v) }
            }
            Note("The same PKCS#8 key OAuth 1.0's RSA-SHA1 uses. A PKCS#1 file is refused with the command to convert it.")
        } else {
            Note("HS256 signs with the client secret above.")
        }
    }

    Field("Scope", auth.scope, hint = "space separated") { v -> edit { it.copy(scope = v) } }
    Field("Audience", auth.audience, hint = "optional") { v -> edit { it.copy(audience = v) } }

    if (auth.grantType == GRANT_AUTH_CODE) {
        CheckRow("Use PKCE", auth.usePkce) { on -> edit { it.copy(usePkce = on) } }
        // The exact string to register with the provider, because a redirect
        // URI that differs by one character is refused with a message that does
        // not say which character.
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormLabel("Redirect")
            PzText(redirectUri(auth), color = P.text, style = Typo.caption)
            Spacer(Modifier.width(6.dp))
            IconActionButton(
                key = AllIconsKeys.Actions.Copy,
                contentDescription = "Copy the redirect URL",
                onClick = { copyToClipboard(redirectUri(auth)) },
            )
        }
        Field("Redirect port", auth.redirectPort.toString()) { v ->
            v.toIntOrNull()?.let { port -> edit { it.copy(redirectPort = port) } }
        }
        Note("Register that exact URL with the provider. BitTrace listens on it only while authorising.")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(LABEL_COLUMN))
        if (busy) {
            // While a socket is bound and a browser is open, the useful button
            // is the one that stops it. Nothing else on this row can act until
            // it does, and an authorisation you have changed your mind about
            // otherwise holds the redirect port for five minutes.
            GhostButton("Stop") { state.cancelAuthorization() }
        } else {
            PrimaryButton(if (interactive) "Get token" else "Fetch token") {
                onProgress(true, "Starting…")
                // Launched on the client state's scope, not the composition's: a
                // browser-based authorisation must survive leaving this tab.
                state.authorize(oauth, auth, onProgress)
            }
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("Refresh", enabled = !busy && token?.refreshToken?.isNotBlank() == true) {
            onProgress(true, "Refreshing…")
            state.refreshToken(oauth, auth, onProgress)
        }
        Spacer(Modifier.width(6.dp))
        GhostButton("Clear", enabled = token != null) {
            state.tokens.clear(auth)
            onProgress(false, null)
        }
    }

    status?.let { Note(it) }

    Row {
        Spacer(Modifier.width(LABEL_COLUMN))
        PzText(
            when {
                token == null -> "No token yet — this request is not authorised."
                token.expired() -> "Token expired. Refresh, or get a new one."
                else -> "Token held."
            },
            color = if (token == null || token.expired()) P.warn else P.ok,
            style = Typo.caption, family = P.Ui,
        )
    }

    // The token itself, once there is one: readable and copyable, and nowhere
    // near `ApiAuth.token`. Writing it there would put a live credential in the
    // request's YAML the next time it was saved — which is the thing the
    // memory-only decision exists to prevent. Sending does not need it here
    // either: with OAuth 2.0 selected the sender attaches it already.
    token?.let { held ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormLabel("Token")
            PzText(
                held.accessToken,
                color = P.text, style = Typo.caption,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                // `fill = false`: the token takes the width it needs and no
                // more, so the copy button sits beside it rather than at the far
                // edge of the pane. A long one still stops at the space
                // available and ellipsises there.
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            IconActionButton(
                key = AllIconsKeys.Actions.Copy,
                contentDescription = "Copy the access token",
                onClick = { copyToClipboard(held.accessToken) },
            )
        }
        if (held.refreshToken.isNotBlank()) {
            Note("A refresh token came with it, so this one renews itself rather than needing another sign-in.")
        }
    }
    Note(
        "Tokens live in memory only and are never written to a collection, so authorise again after a " +
            "restart. What you type here does persist, as a password does.",
    )
}

/** A one-of-several picker, on the form's own label column. */
@Composable
private fun Choice(
    label: String,
    options: List<String>,
    selected: String,
    labelOf: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FormLabel(label)
        Dropdown(
            value = labelOf(selected),
            options = options.map(labelOf),
            width = 200.dp,
        ) { picked -> options.firstOrNull { labelOf(it) == picked }?.let(onSelect) }
    }
}

/** A checkbox on the same column, so it lines up with the fields around it. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(LABEL_COLUMN))
        CheckBox(checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        PzText(label, color = P.dim, style = Typo.label, family = P.Ui)
    }
}

/** One labelled field. The label column is shared, so the form lines up. */
@Composable
private fun Field(label: String, value: String, hint: String = "", onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FormLabel(label)
        TextInput(
            value = value,
            onValueChange = onChange,
            placeholder = hint,
            modifier = Modifier.weight(1f),
        )
    }
}

/** An explanatory line under a scheme's fields, indented to the value column. */
@Composable
private fun Note(text: String) {
    Row {
        Spacer(Modifier.width(LABEL_COLUMN))
        PzText(text, color = P.faint, style = Typo.caption, family = P.Ui)
    }
}

/**
 * What this auth will actually put on the wire.
 *
 * Shows the encoded value rather than masking it: the point of a proxy tool is
 * to see the bytes, and a base64 blob you cannot read is exactly the one you
 * cannot check against what the server logged.
 */
@Composable
private fun Preview(auth: ApiAuth) {
    val header = auth.header()
    val query = auth.queryParam()

    Box(
        Modifier.fillMaxWidth().background(P.panel).topBorder(P.line)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        when {
            header != null -> Sent("header", "${header.first}: ${header.second}")
            query != null -> Sent("query", "${query.first}=${query.second}")
            auth.type == AUTH_NONE ->
                PzText("Sends nothing", color = P.faint, style = Typo.caption, family = P.Ui)

            // Blank fields are sent as blank, so the only way to reach this is
            // an API key with no name — a header or parameter cannot have one.
            auth.type == AUTH_API_KEY -> PzText(
                "Sends nothing — the key needs a name",
                color = P.warn, style = Typo.caption, family = P.Ui,
            )

            else -> PzText(
                "Sends nothing — ${authTypeLabel(auth.type)} is not one this build can send",
                color = P.warn, style = Typo.caption, family = P.Ui,
            )
        }
    }
}

/** The preview line: what kind of thing is sent, then the thing itself. */
@Composable
private fun Sent(kind: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PzText(
            kind, color = P.dim, style = Typo.micro, family = P.Ui, weight = FontWeight.SemiBold,
            modifier = Modifier.width(46.dp),
        )
        PzText(value, color = P.text, style = Typo.caption, family = P.Mono)
    }
}

/** Wide enough for the longest label, so every field in the form lines up. */
/**
 * The label column, and the gap after it.
 *
 * [LABEL_WIDTH] has to fit the longest single *word* any label uses — currently
 * "Authorization". A fixed column narrower than a word does not wrap the label,
 * it breaks the word: "Authorizatio / n URL". Compose only splits mid-word when
 * the word cannot fit at all, so the fix is width, not a wrapping option.
 *
 * The gap is separate rather than baked into the width so that a label which
 * does use the full column still cannot touch the control beside it.
 */
private val LABEL_WIDTH = 104.dp

private val LABEL_GAP = 10.dp

/** What a row indented past the label has to skip: both of them. */
private val LABEL_COLUMN = LABEL_WIDTH + LABEL_GAP

/**
 * A form label and the gap that follows it.
 *
 * One composable rather than a width on each call site, because the gap is the
 * part that gets forgotten — and a label butted against its input is the thing
 * that made this worth fixing.
 */
@Composable
private fun RowScope.FormLabel(text: String, enabled: Boolean = true) {
    PzText(
        text,
        color = if (enabled) P.dim else P.faint,
        style = Typo.label,
        family = P.Ui,
        modifier = Modifier.width(LABEL_WIDTH),
    )
    Spacer(Modifier.width(LABEL_GAP))
}
