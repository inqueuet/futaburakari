package com.valoser.futaburakari

import java.net.URL

object UrlNormalizer {
    /**
     * スレッドを一意に識別するキー。
     * 旧仕様ではドメインを落としていたが、衝突回避のため scheme + host を含める。
     * 例: https://zip.2chan.net/32/res/12345.htm -> https://zip.2chan.net/32#12345
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

    // 互換: 旧キー生成（ドメインを含めない形式）
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
