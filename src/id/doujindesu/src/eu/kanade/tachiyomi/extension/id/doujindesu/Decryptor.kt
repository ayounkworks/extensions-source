package eu.kanade.tachiyomi.extension.id.doujindesu

import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs

class Decryptor(private val apiUrlProvider: () -> String) {

    fun xorInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val apiUrl = apiUrlProvider().toHttpUrl()
        val apiPath = apiUrl.encodedPath.trimEnd('/')
        val isApiRequest = request.url.host == apiUrl.host &&
            request.url.port == apiUrl.port &&
            (request.url.encodedPath == apiPath || request.url.encodedPath.startsWith("$apiPath/"))

        if (!isApiRequest) return@Interceptor chain.proceed(request)

        val response = chain.proceed(request)

        val dto = response.parseAs<EncryptedDto>()
        val decryptedJson = decrypt(dto.encrypted) ?: throw IOException("Decryption failed")

        response.newBuilder()
            .body(decryptedJson.toResponseBody(response.body.contentType()))
            .build()
    }

    // index-*.js
    fun toSigned32(x: Long): Int {
        var y = x and 0xFFFFFFFFL
        if (y >= 0x80000000L) y -= 0x100000000L
        return y.toInt()
    }

    fun wH(e: Int): String {
        val t = SALT + "_" + e
        var a = 0
        for (ch in t) {
            a = (a shl 5) - a + ch.code
            a = toSigned32(a.toLong())
        }
        var l = if (a != 0) abs(a.toLong()) else 123456789L
        return buildString {
            repeat(32) {
                l = (l * 1664525L + 1013904223L) % 4294967296L
                append((33 + (l % 93)).toInt().toChar())
            }
        }
    }

    fun lU(): List<String> {
        val now = System.currentTimeMillis()
        val t = now / HOUR_MS
        return listOf(wH(t.toInt()), wH((t - 1).toInt()), wH((t + 1).toInt()))
    }

    fun yre(encryptedHex: String, key: String): String {
        val bytes = encryptedHex.chunked(2).map { it.toInt(16) }
        var d = 42
        val keyLen = key.length
        return buildString {
            for ((idx, byteVal) in bytes.withIndex()) {
                val keyChar = key[idx % keyLen].code
                val k = byteVal xor keyChar xor (idx * 13) xor d
                append((k and 0xFF).toChar())
                d = (d + byteVal) % 256
            }
        }
    }

    fun decrypt(encryptedHex: String): String? {
        if (
            encryptedHex.isEmpty() ||
            encryptedHex.length % 2 != 0 ||
            encryptedHex.any { it.digitToIntOrNull(16) == null }
        ) {
            return null
        }

        for (key in lU()) {
            val decoded = runCatching {
                URLDecoder.decode(yre(encryptedHex, key), StandardCharsets.UTF_8.name())
            }.getOrNull() ?: continue

            if (decoded.isJsonPayload()) return decoded
        }
        return null
    }

    private fun String.isJsonPayload(): Boolean {
        val payload = trim()
        return (payload.startsWith("{") && payload.endsWith("}")) ||
            (payload.startsWith("[") && payload.endsWith("]"))
    }

    companion object {
        private const val SALT = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2"
        private const val HOUR_MS = 3600000L
    }
}
