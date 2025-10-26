package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import android.os.StatFs
import android.os.Environment
import java.security.SecureRandom

/**
 * アプリ共通の設定値にアクセスするユーティリティ（`SharedPreferences` バックエンド）。
 * `pthc`・`pwd`・GUID 付与フラグ・並列度設定などの永続化に加え、キャッシュ制限や空き容量に関する
 * ヘルパー関数も提供する。
 */
object AppPreferences {
    /** `SharedPreferences` のファイル名。 */
    private const val PREFS_NAME = "futaba_prefs"
    /** `pthc` のキー。 */
    private const val KEY_PTHC = "pthc"
    /** `pwd` のキー。 */
    private const val KEY_PWD = "pwd"
    /** GUID を付与するかどうかのフラグキー。 */
    private const val KEY_APPEND_GUID = "append_guid_on"
    /** 削除されたレスを非表示にするかどうかのフラグキー。 */
    private const val KEY_HIDE_DELETED_RES = "hide_deleted_res"
    /** 重複レスを非表示にするかどうかのフラグキー。 */
    private const val KEY_HIDE_DUPLICATE_RES = "hide_duplicate_res"
    /** 重複レスの非表示閾値を保存するキー。 */
    private const val KEY_DUPLICATE_RES_THRESHOLD = "duplicate_res_threshold"
    /** 全体の並列度（1..4）のキー。 */
    private const val KEY_CONCURRENCY_LEVEL = "concurrency_level"
    /** フル画像アップグレード用の並列度（レガシー。現状は全体と統合）のキー。 */
    private const val KEY_FULL_UPGRADE_CONCURRENCY = "full_upgrade_concurrency"
    /** 低帯域モードの有効フラグ。 */
    private const val KEY_LOW_BANDWIDTH_MODE = "low_bandwidth_mode"

    /** アプリ専用の `SharedPreferences` を返す。 */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 保存された `pthc` を取得（未設定なら `null`）。 */
    fun getPthc(context: Context): String? {
        return getPreferences(context).getString(KEY_PTHC, null)
    }

    /** `pthc` を保存。 */
    fun savePthc(context: Context, pthc: String) {
        getPreferences(context).edit().putString(KEY_PTHC, pthc).apply()
    }

    /** 保存された `pwd` を取得（未設定なら `null`）。 */
    fun getPwd(context: Context): String? {
        return getPreferences(context).getString(KEY_PWD, null)
    }

    /** `pwd` を保存。 */
    fun savePwd(context: Context, pwd: String) {
        getPreferences(context).edit().putString(KEY_PWD, pwd).apply()
    }

    /** レス投稿の下書き本文を保存するキーのプレフィックス。 */
    private const val KEY_REPLY_DRAFT_PREFIX = "reply_draft_"

    /** レス投稿の下書き本文を保存する際のキーを生成する。 */
    private fun buildReplyDraftKey(boardUrl: String, threadId: String): String {
        val boardHash = boardUrl.hashCode()
        return "${KEY_REPLY_DRAFT_PREFIX}${boardHash}_$threadId"
    }

    /**
     * 指定スレッド用のコメント下書きを保存する。
     * 空文字列が渡された場合は保存済み下書きを削除する。
     */
    fun saveReplyDraft(context: Context, boardUrl: String, threadId: String, comment: String) {
        if (boardUrl.isBlank() || threadId.isBlank()) return
        val prefs = getPreferences(context)
        val key = buildReplyDraftKey(boardUrl, threadId)
        if (comment.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, comment).apply()
        }
    }

    /** 指定スレッドのコメント下書きを取得する（未保存時は `null`）。 */
    fun getReplyDraft(context: Context, boardUrl: String, threadId: String): String? {
        if (boardUrl.isBlank() || threadId.isBlank()) return null
        val key = buildReplyDraftKey(boardUrl, threadId)
        return getPreferences(context).getString(key, null)
    }

    /** 指定スレッドのコメント下書きを削除する。 */
    fun clearReplyDraft(context: Context, boardUrl: String, threadId: String) {
        if (boardUrl.isBlank() || threadId.isBlank()) return
        val key = buildReplyDraftKey(boardUrl, threadId)
        getPreferences(context).edit().remove(key).apply()
    }

    /** GUID を付与する設定かどうかを返す。未設定時は `true`。 */
    fun getAppendGuidOn(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_APPEND_GUID, true)
    }

    /** GUID の付与を有効/無効にする。 */
    fun setAppendGuidOn(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_APPEND_GUID, enabled).apply()
    }

    /** 削除されたレスを非表示にする設定かどうかを返す。未設定時は `true`。 */
    fun getHideDeletedRes(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_HIDE_DELETED_RES, true)
    }

    /** 削除されたレスの非表示を有効/無効にする。 */
    fun setHideDeletedRes(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_HIDE_DELETED_RES, enabled).apply()
    }

    /** 重複するレスを非表示にする設定かどうかを返す。未設定時は `false`。 */
    fun getHideDuplicateRes(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_HIDE_DUPLICATE_RES, false)
    }

    /** 重複するレスの非表示を有効/無効にする。 */
    fun setHideDuplicateRes(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_HIDE_DUPLICATE_RES, enabled).apply()
    }

    /** 重複レスを非表示にする際の閾値（n回目以降を隠す）を取得。未設定時は `3`、最小値は `2`。 */
    fun getDuplicateResThreshold(context: Context): Int {
        val raw = getPreferences(context).getInt(KEY_DUPLICATE_RES_THRESHOLD, 3)
        return raw.coerceAtLeast(2)
    }

    /** 重複レスの非表示閾値を保存。2〜20の範囲に丸める。 */
    fun setDuplicateResThreshold(context: Context, threshold: Int) {
        val value = threshold.coerceIn(2, 20)
        getPreferences(context).edit().putInt(KEY_DUPLICATE_RES_THRESHOLD, value).apply()
    }

    /**
     * ユーザー選択の全体並列度（1..4）を取得。
     * 未設定時は 2。返却時は必ず 1..4 に丸める。
     * レガシーのフル画像アップグレード設定があれば初回に統合移行する。
     */
    fun getConcurrencyLevel(context: Context): Int {
        val prefs = getPreferences(context)
        val hasUnified = prefs.contains(KEY_CONCURRENCY_LEVEL)
        val raw = if (hasUnified) {
            prefs.getInt(KEY_CONCURRENCY_LEVEL, 2)
        } else {
            // 旧キー（フル画像アップグレードの並列度）があればそれを読み出して統合キーへ移行
            val legacy = prefs.getInt(KEY_FULL_UPGRADE_CONCURRENCY, 2)
            // 今後の読み出し用に統合キーへ保存
            prefs.edit().putInt(KEY_CONCURRENCY_LEVEL, legacy.coerceIn(1, 4)).apply()
            legacy
        }
        return raw.coerceIn(1, 4)
    }

    /** 全体並列度を保存（1..4に丸める）。 */
    fun setConcurrencyLevel(context: Context, level: Int) {
        val v = level.coerceIn(1, 4)
        getPreferences(context).edit().putInt(KEY_CONCURRENCY_LEVEL, v).apply()
    }

    /** 低帯域モードが有効かどうか。未設定時は `false`。 */
    fun isLowBandwidthModeEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_LOW_BANDWIDTH_MODE, false)
    }

    /** 低帯域モードの有効/無効を保存する。 */
    fun setLowBandwidthMode(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_LOW_BANDWIDTH_MODE, enabled).apply()
    }

    /** フル画像アップグレードの並列度を取得（全体設定と統一）。 */
    fun getFullUpgradeConcurrency(context: Context): Int {
        return getConcurrencyLevel(context)
    }

    /** フル画像アップグレードの並列度を保存（全体設定へ委譲）。 */
    fun setFullUpgradeConcurrency(context: Context, level: Int) {
        setConcurrencyLevel(context, level)
    }

    /**
     * 自分が投稿したレス番号を保存する（スレッドURL → レス番号のセット）。
     * キー: "my_post_numbers_<threadUrl のハッシュ値>"
     */
    fun addMyPostNumber(context: Context, threadUrl: String, resNumber: String) {
        val key = "my_post_numbers_${threadUrl.hashCode()}"
        val prefs = getPreferences(context)
        val existing = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(resNumber)
        prefs.edit().putStringSet(key, existing).apply()
    }

    /**
     * 指定スレッドで自分が投稿したレス番号のセットを取得する。
     */
    fun getMyPostNumbers(context: Context, threadUrl: String): Set<String> {
        val key = "my_post_numbers_${threadUrl.hashCode()}"
        return getPreferences(context).getStringSet(key, emptySet()) ?: emptySet()
    }

    /**
     * 8 桁のランダムな数値文字列を生成（暗号論的に安全な乱数を使用）。
     */
    fun generateNewPwd(): String {
        val secureRandom = SecureRandom()
        return (10000000 + secureRandom.nextInt(90000000)).toString()
    }

    /**
     * 端末の内部ストレージの利用可能容量をGB単位で取得する。
     * 取得に失敗した場合はフォールバックとして 32GB を返す。
     */
    fun getAvailableStorageGB(context: Context): Double {
        return try {
            val stat = StatFs(context.filesDir.path)
            val availableBytes = stat.availableBytes
            availableBytes / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) {
            // フォールバック: 32GBと仮定
            32.0
        }
    }

    /**
     * パーセンテージベースの自動クリーンアップ設定のためのユーティリティ。
     * "0"/"5"/"10"/"20"/"30" のキーを受け取り、利用可能容量に対する割合から実際のバイト数を計算する。
     * 未知のキーは安全策として 0（無効）にフォールバックする。
     */
    fun calculateCacheLimitBytes(context: Context, percentageKey: String): Long {
        val availableGB = getAvailableStorageGB(context)
        val availableBytes = (availableGB * 1024 * 1024 * 1024).toLong()

        return when (percentageKey) {
            "0" -> 0L // 無効
            "5" -> (availableBytes * 0.05).toLong()
            "10" -> (availableBytes * 0.10).toLong()
            "20" -> (availableBytes * 0.20).toLong()
            "30" -> (availableBytes * 0.30).toLong()
            else -> 0L
        }
    }

    /**
     * 既存の固定値設定(MB)を提供しているパーセンテージの選択肢(0/5/10/20/30)に丸め込んで変換する。
     * 移行用の関数で、未知の値や 0 以下は無効("0")扱いとする。
     */
    fun migrateLegacyCacheLimit(context: Context, legacyMB: String): String {
        val availableGB = getAvailableStorageGB(context)
        val availableBytes = (availableGB * 1024 * 1024 * 1024).toLong()
        val legacyBytes = (legacyMB.toLongOrNull() ?: 0L) * 1024 * 1024

        if (legacyBytes <= 0) return "0"

        val percentage = (legacyBytes.toDouble() / availableBytes * 100).toInt()

        return when {
            percentage <= 7 -> "5"
            percentage <= 15 -> "10"
            percentage <= 25 -> "20"
            else -> "30"
        }
    }
}
