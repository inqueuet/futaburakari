package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.NgManagerScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

/**
 * NG（除外）ルールを管理するアクティビティ。
 *
 * - 画面は Jetpack Compose で構築され、`NgManagerScreen` を表示します。
 * - インテントのエクストラで対象のルール種別やタイトル表示有無を制御できます。
 * - ルール操作（追加・更新・削除）は `NgStore` を介して行い、操作後に一覧を再読み込みします。
 */
class NgManagerActivity : BaseActivity() {
    companion object {
        /**
         * 対象ルールの絞り込みを指定するエクストラキー。
         * 値には `RuleType` の `name`（例: "TITLE"、"BODY"、"ID"）を渡します。
         */
        const val EXTRA_LIMIT_RULE_TYPE = "extra_limit_rule_type"
        /**
         * タイトル表示有無を指定するエクストラキー。
         * `true` でタイトル関連オプションを非表示にします。
         */
        const val EXTRA_HIDE_TITLE = "extra_hide_title"
    }

    /** NG ルールの永続化・取得を担うストア。アクティビティ生成時に初期化されます。 */
    private lateinit var store: NgStore
    /** 画面に表示するルールの種別を制限する場合の指定。未指定なら全件表示。 */
   private var limitType: RuleType? = null
    /** タイトル関連のオプションを非表示にするかどうかのフラグ。 */
    private var hideTitle: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = NgStore(this)
        store.cleanup()
        limitType = intent.getStringExtra(EXTRA_LIMIT_RULE_TYPE)?.let {
            runCatching { RuleType.valueOf(it) }.getOrNull()
        }
        hideTitle = intent.getBooleanExtra(EXTRA_HIDE_TITLE, false)

        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                var rules by remember { mutableStateOf(currentRules()) }
                fun refresh() { rules = currentRules() }

                NgManagerScreen(
                    title = when (limitType) {
                        RuleType.TITLE -> "NG管理（スレタイ）"
                        RuleType.BODY -> "NG管理（本文）"
                        RuleType.ID -> "NG管理（ID）"
                        null -> "NG管理"
                    },
                    rules = rules,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onAddRule = { type, pattern, match ->
                        store.addRule(type, pattern, match)
                        refresh()
                    },
                    onUpdateRule = { ruleId, pattern, match ->
                        store.updateRule(ruleId, pattern, match)
                        refresh()
                    },
                    onDeleteRule = { ruleId ->
                        store.removeRule(ruleId)
                        refresh()
                    },
                    limitType = limitType,
                    hideTitleOption = hideTitle
                )
            }
        }
    }

    /**
     * 現在のルール一覧を取得します。
     *
     * - `limitType` が指定されている場合は、その種別に一致するルールのみを返します。
     * - 未指定の場合は全ルールを返します。
     */
    private fun currentRules(): List<NgRule> {
        val all = store.getRules()
        return limitType?.let { t -> all.filter { it.type == t } } ?: all
    }
}
