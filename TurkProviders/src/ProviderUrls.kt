package com.turk

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Keep query escaping separate from path escaping. Never rewrite signed media queries. */
fun encodeQuery(value: String): String = URLEncoder.encode(value.trim(), "UTF-8")
fun encodePathSegment(value: String): String = encodeQuery(value).replace("+", "%20")

fun resolveHttpUrl(raw: String?, base: String? = null): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        val text = raw.trim()
        if (text.any { it.isISOControl() }) return null
        val uri = if (base.isNullOrBlank()) URI(text) else URI(base).resolve(text)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) null
        else uri.toASCIIString()
    } catch (_: Exception) { null }
}

fun httpOrigin(url: String): String? = try {
    val uri = URI(resolveHttpUrl(url) ?: "")
    if (uri.host == null) null else "${uri.scheme}://${uri.rawAuthority}"
} catch (_: Exception) { null }

fun queryValue(url: String, key: String): String? = try {
    URI(url).rawQuery?.split('&')?.firstNotNullOfOrNull { item ->
        val pair = item.split('=', limit = 2)
        if (URLDecoder.decode(pair[0], "UTF-8") == key) URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8") else null
    }
} catch (_: Exception) { null }
