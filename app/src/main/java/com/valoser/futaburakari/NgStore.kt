package com.valoser.futaburakari

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

class NgStore(private val context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()
    private val key = "ng_rules_json"

    fun getRules(): List<NgRule> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<NgRule>>() {}.type
            gson.fromJson<List<NgRule>>(json, type)
        }.getOrElse { emptyList() }
    }

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

    fun removeRule(ruleId: String) {
        val updated = getRules().filterNot { it.id == ruleId }
        save(updated)
    }

    fun updateRule(ruleId: String, pattern: String, match: MatchType?) {
        val updated = getRules().map {
            if (it.id == ruleId) it.copy(pattern = pattern, match = match) else it
        }
        save(updated)
    }

    private fun save(rules: List<NgRule>) {
        prefs.edit().putString(key, gson.toJson(rules)).apply()
    }

    fun cleanup() {
        val rules = getRules()
        if (rules.isEmpty()) return
        val keys = HistoryManager.getAll(context).map { it.key }.toSet()
        val now = System.currentTimeMillis()
        val ttl = 3L * 24 * 60 * 60 * 1000 // 3 days
        val filtered = rules.filterNot { r ->
            val isEphemeral = r.ephemeral == true
            if (!isEphemeral) return@filterNot false

            val created = r.createdAt ?: 0L
            val tooOld = created > 0L && (now - created >= ttl)
            val hasSource = !r.sourceKey.isNullOrBlank()
            val sourceExists = hasSource && (r.sourceKey in keys)

            when {
                // 履歴に紐づく一時NG: 履歴が消えたら削除（経過日数は無視）
                hasSource -> !sourceExists
                // 履歴に紐付かない一時NG: 3日超過で削除
                else -> tooOld
            }
        }
        if (filtered.size != rules.size) save(filtered)
    }
}
