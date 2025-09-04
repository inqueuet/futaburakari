package com.valoser.futaburakari

import java.net.URL

object UrlNormalizer {
    /**
     * スレッドを一意に識別するキー。
     * 旧仕様ではドメインを落としていたが、衝突回避のため scheme + host を含める。
     * - `protocol` と `host` は小文字化する（ポート番号は含めない）。
     * - パスは `/res/` より前の部分のみを採用（先頭/末尾の `/` は除去）。
     * - 末尾の `.htm` を除いた最終セグメントをスレ番号として `#<resNum>` を付与。
     * 例: https://zip.2chan.net/32/res/12345.htm → https://zip.2chan.net/32#12345
     * 例外時はフォールバックとして元の文字列を返す。
     */
    fun threadKey(url: String): String = try {
        val u = URL(url)
        val path = u.path // 例: /32/res/12345.htm
        val boardPath = path.substringBeforeLast("/res/").trim('/') // 例: 32
        val threadId = path.substringAfterLast('/').substringBefore(".htm")
        "${u.protocol.lowercase()}://${u.host.lowercase()}/${boardPath}#$threadId"
    } catch (e: Exception) {
        url // フォールバック
    }

    /**
     * 互換: 旧キー生成（スキームは含めないが、ホストを含む形式）。
     * - 入力URLの先頭 `scheme://` を取り除き、そこから最後の `/` までをベース部とする。
     *   これにはホスト名とパス（例: `zip.2chan.net/32/res`）が含まれる。
     * - 末尾の `.htm` を除いた最終セグメントをスレ番号として `#<resNum>` を付与。
     * 例: https://zip.2chan.net/32/res/12345.htm → zip.2chan.net/32/res#12345
     * 例外時はフォールバックとして元の文字列を返す。
     */
    fun legacyThreadKey(url: String): String = try {
        val threadId = url.substringAfterLast("/").substringBefore(".htm")
        val boardPath = url.substringAfter("://")
            .substringAfter("/")
            .substringBeforeLast("/")
        "$boardPath#$threadId"
    } catch (e: Exception) {
        url
    }
}
