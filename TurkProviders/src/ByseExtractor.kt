package com.turk

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class ByseExtractor(
    override var name: String = "Byse",
    override var mainUrl: String = "https://bysejikuar.com"
) : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileCode = url.substringAfter("/e/").substringBefore("?").substringBefore("\"").substringBefore("'").trimEnd('/')
        if (fileCode.isBlank()) return

        val domain = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl
        val apiUrl = "$domain/api/videos/$fileCode/"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to url
        )

        try {
            val res = app.get(apiUrl, headers = headers).text
            val json = JSONObject(res)
            val playback = json.optJSONObject("playback") ?: return

            val version = playback.optString("version").toIntOrNull() ?: 3
            val keyParts = playback.optJSONArray("key_parts") ?: return
            val ivStr = playback.optString("iv")
            val payloadStr = playback.optString("payload")
            if (ivStr.isBlank() || payloadStr.isBlank() || keyParts.length() == 0) return

            val p1 = keyParts.optString(version - 1)
            val p2 = keyParts.optString(31 - version - 1)
            if (p1.isBlank() || p2.isBlank()) return

            val b1 = base64UrlDecode(p1)
            val b2 = base64UrlDecode(p2)
            val key = ByteArray(b1.size + b2.size)
            System.arraycopy(b1, 0, key, 0, b1.size)
            System.arraycopy(b2, 0, key, b1.size, b2.size)

            val iv = base64UrlDecode(ivStr)
            val payload = base64UrlDecode(payloadStr)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val decryptedBytes = cipher.doFinal(payload)
            val decryptedJson = String(decryptedBytes, Charsets.UTF_8)

            val decObj = JSONObject(decryptedJson)
            val sources = decObj.optJSONArray("sources") ?: return

            for (i in 0 until sources.length()) {
                val src = sources.getJSONObject(i)
                val streamUrl = src.optString("url")
                if (streamUrl.isBlank()) continue
                val label = src.optString("label", "720p")
                val quality = when {
                    label.contains("1080") -> Qualities.P1080.value
                    label.contains("720") -> Qualities.P720.value
                    label.contains("480") -> Qualities.P480.value
                    label.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name $label",
                        url = streamUrl,
                        referer = "$domain/",
                        quality = quality,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun base64UrlDecode(str: String): ByteArray {
        var s = str.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) {
            s += "="
        }
        return Base64.decode(s, Base64.DEFAULT)
    }
}

open class ByseJikuar : ByseExtractor(name = "ByseJikuar", mainUrl = "https://bysejikuar.com")
open class ByseZoxexe : ByseExtractor(name = "ByseZoxexe", mainUrl = "https://bysezoxexe.com")
