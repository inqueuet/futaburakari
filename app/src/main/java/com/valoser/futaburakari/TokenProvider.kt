package com.valoser.futaburakari

/**
 * 投稿ページなどから送信に必要なトークン群を取得するためのプロバイダ。
 * 実装は事前準備（UA 設定など）を行い、指定 URL からトークンを解析して返します。
 */
interface TokenProvider {
    /**
     * プロバイダの初期化処理を行います。
     * 必要に応じて `userAgent` を設定します（未指定可）。
     */
    fun prepare(userAgent: String? = null)

    /**
     * 指定した投稿ページ URL からトークンを取得します。
     * @param postPageUrl トークンを取得する対象の URL
     * @return トークン名をキー、値をバリューとするマップを `Result` で返します。
     *         解析失敗や通信エラー等は `Result.failure` となります。
     */
    suspend fun fetchTokens(postPageUrl: String): Result<Map<String, String>>
}
