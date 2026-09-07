package org.bittrace.api

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration

/**
 * Reads and writes a request's YAML file.
 *
 * Bodies are the reason this uses a real YAML library rather than string
 * building: a correct literal block scalar has to cope with leading whitespace,
 * trailing whitespace (which block scalars cannot represent at all), CRLF,
 * lines that read as `---`, and chomping indicators. Getting any of those wrong
 * silently corrupts someone's request body.
 */
/**
 * The plain YAML configuration, shared by every store that writes one.
 *
 * `encodeDefaults` so a file always shows every field — one that omits what it
 * left at the default is a file you cannot diff. `strictMode = false` so a file
 * from a later build loads rather than throwing, the same bargain
 * `SettingsStore` makes with `ignoreUnknownKeys`.
 *
 * Requests keep their own instance below: only a body needs literal block
 * scalars, and that option is what makes one readable in a diff.
 */
val appYaml = Yaml(
    configuration = YamlConfiguration(encodeDefaults = true, strictMode = false),
)

object RequestYaml {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            encodeDefaults = true,
            // Unknown keys are ignored rather than fatal, matching how
            // SettingsStore treats a settings file from a newer build.
            strictMode = false,
            // Bodies come out as readable block scalars instead of one long
            // double-quoted line full of \n escapes.
            multiLineStringStyle = MultiLineStringStyle.Literal,
        ),
    )

    fun encode(request: ApiRequest): String = yaml.encodeToString(ApiRequest.serializer(), normalize(request))

    fun decode(text: String): ApiRequest = migrate(yaml.decodeFromString(ApiRequest.serializer(), text))

    /**
     * Folds a pre-settings `timeoutMs` into [ApiRequest.settings].
     *
     * Every request saved before settings existed carries `timeoutMs: 30000` on
     * disk, because this encoder writes defaults. Reading that verbatim would
     * pin each of them to 30 seconds forever — opted out of a default they never
     * chose — so only a value that differs from the old default is carried
     * across, as the one that somebody actually set.
     *
     * A request that already has settings is left alone: the field cannot be
     * written any more, so anything holding both was written by hand.
     */
    private fun migrate(request: ApiRequest): ApiRequest {
        val legacy = request.timeoutMs ?: return request
        val settings = if (legacy != LEGACY_DEFAULT_TIMEOUT_MS && request.settings.timeoutMs == null) {
            request.settings.copy(timeoutMs = legacy)
        } else {
            request.settings
        }
        return request.copy(settings = settings, timeoutMs = null)
    }

    /**
     * CRLF forces the emitter out of block style and into escaped double quotes,
     * so line endings are normalised on the way to disk. The editor produces
     * `\n` anyway; this catches pasted content.
     */
    /** What [ApiRequest.timeoutMs] defaulted to before it became a setting. */
    private const val LEGACY_DEFAULT_TIMEOUT_MS = 30_000L

    private fun normalize(request: ApiRequest): ApiRequest =
        if (!request.body.text.contains('\r')) {
            request
        } else {
            request.copy(body = request.body.copy(text = request.body.text.replace("\r\n", "\n").replace('\r', '\n')))
        }
}
