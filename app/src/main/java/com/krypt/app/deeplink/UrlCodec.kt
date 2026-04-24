package com.krypt.app.deeplink

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Tiny URL build / parse helper scoped to Krypt's deep-link grammar.
 *
 * We do NOT use `android.net.Uri` here because that class is unavailable on
 * pure JVM (it would force every unit test onto Robolectric). All Krypt URLs
 * fit the simple shape `krypt://<authority>?<k>=<v>&<k>=<v>&...` where both
 * keys and values are standard RFC 3986 query characters; that shape is
 * trivially parseable without a platform Uri library.
 *
 * Android callers that need an `android.net.Uri` (e.g. for an Intent) just
 * do `Uri.parse(builtString)` at the call site — this keeps the heavy-lifting
 * library-free.
 */
internal object UrlCodec {

    /**
     * Build a URL of the form `krypt://<authority>?k1=v1&k2=v2&...`.
     *
     * Values are URL-encoded (UTF-8, RFC 3986 style). The iteration order of
     * [params] is preserved, which is contract-important for the MAC-covered
     * `krypt://paired` URL (but not for shape-only URLs).
     */
    fun build(authority: String, params: List<Pair<String, String>>): String {
        require(authority.isNotEmpty()) { "authority must not be empty" }
        val base = "${DeepLinkScheme.SCHEME}://$authority"
        if (params.isEmpty()) return base
        return buildString {
            append(base).append('?')
            params.forEachIndexed { i, (k, v) ->
                if (i > 0) append('&')
                append(percentEncode(k))
                append('=')
                append(percentEncode(v))
            }
        }
    }

    /**
     * Parse a Krypt deep-link URL; returns (authority, query-map) on success.
     *
     * Validates only that the URL starts with the Krypt scheme and follows
     * the two-component path. Per-authority validation is the caller's job.
     *
     * Returns `null` if the URL is malformed (no scheme, no authority,
     * unparseable query).
     */
    fun parse(url: String): Parsed? {
        if (!url.startsWith("${DeepLinkScheme.SCHEME}://")) return null
        val afterScheme = url.substring(DeepLinkScheme.SCHEME.length + 3)
        if (afterScheme.isEmpty()) return null

        val (authorityPart, queryPart) = afterScheme.split('?', limit = 2).let {
            it[0] to it.getOrNull(1)
        }
        if (authorityPart.isEmpty()) return null

        // Strip any path segments (our scheme has none; reject if present
        // rather than silently accept).
        if (authorityPart.contains('/')) return null

        val params = mutableMapOf<String, String>()
        if (queryPart != null && queryPart.isNotEmpty()) {
            for (pair in queryPart.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq <= 0) return null
                val key = tryPercentDecode(pair.substring(0, eq)) ?: return null
                val value = tryPercentDecode(pair.substring(eq + 1)) ?: return null
                // Duplicate keys are rejected: our protocol never uses them
                // and duplication would let an attacker shadow parameters.
                if (params.put(key, value) != null) return null
            }
        }
        return Parsed(authority = authorityPart, params = params)
    }

    data class Parsed(val authority: String, val params: Map<String, String>)

    // Standard RFC 3986 application/x-www-form-urlencoded encoding. Kotlin's
    // URLEncoder produces '+' for space rather than '%20'; that's fine for
    // query parameters.
    private fun percentEncode(raw: String): String =
        URLEncoder.encode(raw, Charsets.UTF_8)

    private fun tryPercentDecode(raw: String): String? = try {
        URLDecoder.decode(raw, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}
