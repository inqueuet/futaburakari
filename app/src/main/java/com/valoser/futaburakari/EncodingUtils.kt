package com.valoser.futaburakari

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * バイト列の文字コードを推定し、文字列にデコードするユーティリティ。
 * 優先度: Content-Type明示 > UTF-8 BOM > UTF-8 妥当性 > 既定(SJIS/Windows-31J)。
 */
object EncodingUtils {
    /** 既定の日本語レガシー系として Windows-31J（MS932/SJIS相当）を採用。 */
    private val SJIS: Charset = Charset.forName("Windows-31J")

    /**
     * バイト列の文字コードを推定して返す。
     * @param bytes 判定対象の全バイト
     * @param contentTypeHeader 例: "text/html; charset=Shift_JIS"（null可）
     * @return 推定した `Charset`。不明時は Windows-31J。
     */
    fun detectCharset(bytes: ByteArray, contentTypeHeader: String?): Charset {
        // 1) Content-Type ヘッダの charset を最優先
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

    /** Content-Typeを考慮してバイト列を文字列にデコードする。 */
    fun decode(bytes: ByteArray, contentTypeHeader: String?): String {
        val cs = detectCharset(bytes, contentTypeHeader)
        return String(bytes, cs)
    }

    /** Content-Typeヘッダ値から `charset=...` を抜き出して可能なら `Charset` に解決（失敗時は null）。 */
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

    /** 先頭3バイトが UTF-8 BOM (EF BB BF) かを判定。 */
    private fun hasUtf8Bom(b: ByteArray): Boolean {
        if (b.size < 3) return false
        return (b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
    }

    /** UTF-8 デコーダを厳密設定（不正入力は例外）で走らせ、成功すればUTF-8らしいと判断。 */
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

