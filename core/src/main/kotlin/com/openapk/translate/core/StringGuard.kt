package com.openapk.translate.core

/**
 * Heuristics that keep the translated app from breaking: we never touch
 * keys, ids, urls, preference values, and we keep format placeholders intact.
 */
object StringGuard {
    private val badNameParts = setOf(
        "key", "keys", "id", "ids", "url", "urls", "uri", "api", "apikey", "token", "secret",
        "authority", "authorities", "scheme", "host", "path", "package", "pkg", "classname",
        "format", "pattern", "regex", "mime", "endpoint", "domain", "value", "values", "sku",
        "json", "sha1", "sha256", "hash", "font", "fontfamily", "typeface", "dsn", "salt",
        "deeplink", "intent", "provider", "permission", "locale", "lang", "language_code", "tz",
    )
    private val badPrefixes = listOf("google_", "firebase_", "gcm_", "com_crashlytics", "fcm_")

    fun isTranslatableName(name: String): Boolean {
        if (name.isEmpty()) return true
        if (name.contains('.')) return false
        val n = name.lowercase()
        if (badPrefixes.any { n.startsWith(it) }) return false
        if (name.endsWith("Id") || name.endsWith("ID") || name.endsWith("Key") || name.endsWith("Url")) return false
        return n.split('_', '-').none { it in badNameParts }
    }

    private val url = Regex("""(?i)(://|^www\.|^[\w.+-]+@[\w-]+\.[\w.]+$|^[a-z][\w+.-]*:[^\s]*$)""")
    private val identifier = Regex("""^[\p{L}\p{N}_.\-:/@#$]+$""")
    private val camel = Regex("""\p{Ll}\p{Lu}""")

    fun shouldTranslate(text: String, sourceIsLatin: Boolean): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 4000) return false
        if (t.none { it.isLetter() }) return false
        if (url.containsMatchIn(t)) return false
        if (t.none { it.isWhitespace() } && identifier.matches(t)) {
            if (t.any { it in "_.:/@#$" }) return false
            if (camel.containsMatchIn(t)) return false
            if (t.all { !it.isLetter() || it.isLowerCase() }) return false // "light", "dark" – likely values
            if (t.length > 1 && t.all { !it.isLetter() || it.isUpperCase() } && t.length > 12) return false
        }
        if (sourceIsLatin && t.none { it in 'a'..'z' || it in 'A'..'Z' }) return false
        return true
    }

    /** printf placeholders, {named} / {0} placeholders, html-ish tags, entities */
    val TOKEN = Regex(
        """%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[sSdfxXcboeEgGaAhHn%]|%(\d+\$)?[tT][a-zA-Z]|\{\{?[\w.$]*\}\}?|\$\{[^}]*\}|<[^>]{1,200}>|&[a-zA-Z]+;|&#\d+;"""
    )

    fun tokens(s: String): List<String> = TOKEN.findAll(s).map { it.value }.sorted().toList()

    fun safeResult(original: String, result: String): Boolean {
        if (result.isBlank() || result.length > 6000) return false
        if (tokens(original) != tokens(result)) return false
        if (original.count { it == '%' } != result.count { it == '%' }) return false
        if (original.count { it == '\n' } != result.count { it == '\n' }) return false
        return true
    }
}
