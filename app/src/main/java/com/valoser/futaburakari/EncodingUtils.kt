package com.valoser.futaburakari

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object EncodingUtils {
    private val SJIS: Charset = Charset.forName("Windows-31J") // SJIS系の実運用向け

    fun detectCharset(bytes: ByteArray, contentTypeHeader: String?): Charset {
        // 1) Content-Type ヘッダの charset を優先
        contentTypeHeader
            ?.let { extractCharsetFromContentType(it) }
            ?.let { return it }

        // 2) UTF-8 BOM
        if (hasUtf8Bom(bytes)) return StandardCharsets.UTF_8

        // 3) UTF-8 妥当性チェック
        if (looksUtf8(bytes)) return StandardCharsets.UTF_8

        // 4) 既定: SJIS（Windows-31J）
        return SJIS
    }

    fun decode(bytes: ByteArray, contentTypeHeader: String?): String {
        val cs = detectCharset(bytes, contentTypeHeader)
        return String(bytes, cs)
    }

    private fun extractCharsetFromContentType(ct: String): Charset? {
        // 例: text/html; charset=Shift_JIS
        val m = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(ct)
        val name = m?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'', ' ')
        if (name.isNullOrBlank()) return null
        return when (name.lowercase()) {
            "utf-8", "utf8" -> StandardCharsets.UTF_8
            "shift_jis", "shift-jis", "ms932", "cp932", "windows-31j", "sjis" -> SJIS
            else -> runCatching { Charset.forName(name) }.getOrNull()
        }
    }

    private fun hasUtf8Bom(b: ByteArray): Boolean {
        if (b.size < 3) return false
        return (b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
    }

    private fun looksUtf8(b: ByteArray): Boolean {
        return try {
            val dec = StandardCharsets.UTF_8.newDecoder()
            dec.onMalformedInput(CodingErrorAction.REPORT)
            dec.onUnmappableCharacter(CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(b))
            true
        } catch (_: Exception) {
            false
        }
    }
}

