package com.example.hutaburakari

object UrlNormalizer {
    fun threadKey(url: String): String = try {
        val threadId = url.substringAfterLast("/").substringBefore(".htm")
        val boardPath = url.substringAfter("://")
            .substringAfter("/")      // ドメイン除去
            .substringBeforeLast("/") // 末尾ファイル除去
        "$boardPath#$threadId"
    } catch (e: Exception) {
        url // フォールバック
    }
}