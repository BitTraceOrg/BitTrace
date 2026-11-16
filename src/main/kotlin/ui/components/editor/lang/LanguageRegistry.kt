package org.bittrace.ui.components.editor

/**
 * Every language the editor knows, one instance each for the life of the
 * process.
 *
 * Built together on first use — or at startup, from `EditorEngine.init` — and
 * never again. A language is a description, not a parse: each editor's
 * highlighter derives its own states from it, so sharing one instance between
 * every editor showing JSON is both safe and the point.
 */
internal object LanguageRegistry {
    enum class Lang { GRAPHQL, JSON, HTML, XML, CSS, TYPESCRIPT, JAVASCRIPT, PLAIN }

    private val languages: Map<Lang, EditorLanguage> by lazy { Lang.entries.associateWith(::build) }

    fun init() {
        languages
    }

    operator fun get(kind: Lang): EditorLanguage = languages.getValue(kind)

    val plain: EditorLanguage get() = get(Lang.PLAIN)

    private fun build(kind: Lang): EditorLanguage = when (kind) {
        Lang.GRAPHQL -> EditorLanguage("graphql", GraphQlTokenizer, "()[]{}", FoldStyle.BRACKETS)
        Lang.JSON -> EditorLanguage("json", JsonTokenizer, "[]{}", FoldStyle.BRACKETS)
        Lang.HTML -> EditorLanguage("html", MarkupTokenizer(html = true), "", FoldStyle.TAGS)
        Lang.XML -> EditorLanguage("xml", MarkupTokenizer(html = false), "", FoldStyle.TAGS)
        Lang.CSS -> EditorLanguage("css", CssTokenizer, "()[]{}", FoldStyle.BRACKETS)
        Lang.TYPESCRIPT -> EditorLanguage("typescript", scriptTokenizer(typescript = true), "()[]{}", FoldStyle.BRACKETS)
        Lang.JAVASCRIPT -> EditorLanguage("javascript", scriptTokenizer(typescript = false), "()[]{}", FoldStyle.BRACKETS)
        Lang.PLAIN -> EditorLanguage("text", PlainTokenizer)
    }
}

/**
 * The language for a content type, falling back to plain text.
 *
 * The matching is the loose kind the body formatters use — a content type in
 * the wild is `application/vnd.api+json; charset=utf-8` far more often than it
 * is `application/json`. The same instance comes back for the same language
 * every time, which is how the editor knows a switch is a no-op.
 */
fun languageFor(contentType: String): EditorLanguage {
    val type = contentType.lowercase()
    val kind = when {
        type.contains("graphql") -> LanguageRegistry.Lang.GRAPHQL
        type.contains("json") -> LanguageRegistry.Lang.JSON
        type.contains("html") -> LanguageRegistry.Lang.HTML
        type.contains("xml") -> LanguageRegistry.Lang.XML
        type.contains("css") -> LanguageRegistry.Lang.CSS
        type.contains("typescript") -> LanguageRegistry.Lang.TYPESCRIPT
        type.contains("javascript") || type.contains("ecmascript") -> LanguageRegistry.Lang.JAVASCRIPT
        else -> LanguageRegistry.Lang.PLAIN
    }
    return LanguageRegistry[kind]
}
