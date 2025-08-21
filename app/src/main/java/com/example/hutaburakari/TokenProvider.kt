package com.example.hutaburakari

/**
 * 投稿ページから hidden/token 群を取り出すためのインターフェース。
 * 不可視 WebView ワーカーがこれを実装する。
 */
interface TokenProvider {
    /**
     * @param postPageUrl 例: https://may.2chan.net/27/futaba.php?mode=post&res=323716
     * @return 成功なら hidden/token の Map（"hash","js","pthc" など）
     */
    suspend fun fetchTokens(postPageUrl: String): Result<Map<String, String>>
}