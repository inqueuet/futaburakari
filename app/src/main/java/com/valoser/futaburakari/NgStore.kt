package com.valoser.futaburakari

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * NG ルールを永続化・取得するためのシンプルなストア。
 *
 * - 端末ローカルの `SharedPreferences` に JSON 文字列として保存します（キー: `ng_rules_json`）。
 * - シリアライズ／デシリアライズには `Gson` を使用します。
 * - 一時（ephemeral）ルールのクリーンアップ機能を提供します。
 */
class NgStore(private val context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()
    private val key = "ng_rules_json"

    /**
     * 保存されている NG ルール一覧を取得します。
     *
     * 保存がない場合や JSON パースに失敗した場合は空リストを返します。
     */
    fun getRules(): List<NgRule> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<NgRule>>() {}.type
            gson.fromJson<List<NgRule>>(json, type)
        }.getOrElse { emptyList() }
    }

    /**
     * NG ルールを新規追加して保存します。
     *
     * @param type 対象種別。
     * @param pattern 照合パターン。
     * @param match 照合方式（任意）。
     * @param sourceKey 由来を示すキー（任意）。
     * @param ephemeral 一時ルールかどうか（デフォルト false）。
     * @param createdAt 作成時刻（デフォルトで現在時刻）。
     * @return 追加されたルール。
     */
    fun addRule(
        type: RuleType,
        pattern: String,
        match: MatchType? = null,
        sourceKey: String? = null,
        ephemeral: Boolean = false,
        createdAt: Long = System.currentTimeMillis()
    ): NgRule {
        val new = NgRule(
            id = UUID.randomUUID().toString(),
            type = type,
            pattern = pattern,
            match = match,
            createdAt = createdAt,
            sourceKey = sourceKey,
            ephemeral = ephemeral
        )
        val updated = getRules() + new
        save(updated)
        return new
    }

    /**
     * 指定 ID の NG ルールを削除して保存します。
     */
    fun removeRule(ruleId: String) {
        val updated = getRules().filterNot { it.id == ruleId }
        save(updated)
    }

    /**
     * 指定 ID の NG ルールのパターン／照合方式を更新して保存します。
     */
    fun updateRule(ruleId: String, pattern: String, match: MatchType?) {
        val updated = getRules().map {
            if (it.id == ruleId) it.copy(pattern = pattern, match = match) else it
        }
        save(updated)
    }

    /**
     * ルール一覧を JSON 化して `SharedPreferences` に保存します。
     */
    private fun save(rules: List<NgRule>) {
        prefs.edit().putString(key, gson.toJson(rules)).apply()
    }

    /**
     * 一時（ephemeral）ルールのクリーンアップを行います。
     *
     * - 参照元（`sourceKey`）が存在する一時ルール: 対応する履歴が消えていれば削除します（経過日数は無視）。
     * - 参照元がない一時ルール: 作成から 3 日以上経過していれば削除します。
     * - 一時ルール以外は対象外です。
     */
    fun cleanup() {
        val rules = getRules()
        if (rules.isEmpty()) return
        val keys = HistoryManager.getAll(context).map { it.key }.toSet()
        val now = System.currentTimeMillis()
        val ttl = 3L * 24 * 60 * 60 * 1000 // 3 日（ミリ秒）
        val filtered = rules.filterNot { r ->
            val isEphemeral = r.ephemeral == true
            if (!isEphemeral) return@filterNot false

            val created = r.createdAt ?: 0L
            val tooOld = created > 0L && (now - created >= ttl)
            val hasSource = !r.sourceKey.isNullOrBlank()
            val sourceExists = hasSource && (r.sourceKey in keys)

            when {
                // 履歴に紐づく一時 NG: 履歴が消えたら削除（経過日数は無視）
                hasSource -> !sourceExists
                // 履歴に紐付かない一時 NG: 3 日超過で削除
                else -> tooOld
            }
        }
        if (filtered.size != rules.size) save(filtered)
    }
}
