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

class NgManagerActivity : BaseActivity() {
    companion object {
        const val EXTRA_LIMIT_RULE_TYPE = "extra_limit_rule_type"
        const val EXTRA_HIDE_TITLE = "extra_hide_title"
    }

    private lateinit var store: NgStore
    private var limitType: RuleType? = null
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

    private fun currentRules(): List<NgRule> {
        val all = store.getRules()
        return limitType?.let { t -> all.filter { it.type == t } } ?: all
    }
}
