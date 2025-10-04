package com.valoser.futaburakari

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import com.valoser.futaburakari.ui.compose.NgManagerScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.AndroidEntryPoint

/**
 * NG（除外）ルールを管理するアクティビティ。
 *
 * - 画面は Jetpack Compose で構築され、`NgManagerScreen` を表示します。
 * - インテントのエクストラで対象のルール種別やスレタイ専用 UI の表示可否を制御できます。
 * - データ操作は `NgManagerViewModel` 経由で行い、IO での処理完了後に UI 状態を更新します。
 */
@AndroidEntryPoint
class NgManagerActivity : BaseActivity() {
    companion object {
        /**
         * 対象ルールの絞り込みを指定するエクストラキー。
         * 値には `RuleType` の `name`（例: "TITLE"、"BODY"、"ID"）を渡します。
         */
        const val EXTRA_LIMIT_RULE_TYPE = "extra_limit_rule_type"
        /**
         * スレタイ向けの操作（追加メニューやフィルタチップなど）の表示可否を指定するエクストラキー。
         * `true` を渡すとスレタイ専用の UI 要素を非表示にします。
         */
        const val EXTRA_HIDE_TITLE = "extra_hide_title"
    }

    private val viewModel: NgManagerViewModel by viewModels()
    /** 画面に表示するルールの種別を制限する場合の指定。未指定なら全件表示。 */
    private var limitType: RuleType? = null
    /** スレタイ専用の操作 UI を隠すかどうかを表すフラグ。 */
    private var hideTitle: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        limitType = intent.getStringExtra(EXTRA_LIMIT_RULE_TYPE)?.let {
            runCatching { RuleType.valueOf(it) }.getOrNull()
        }
        hideTitle = intent.getBooleanExtra(EXTRA_HIDE_TITLE, false)


        setContent {
            FutaburakariTheme(expressive = true) {
                val uiState by viewModel.uiState.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(uiState.errorMessage) {
                    uiState.errorMessage?.let { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        viewModel.consumeError()
                    }
                }

                val rules = limitType?.let { type ->
                    uiState.rules.filter { it.type == type }
                } ?: uiState.rules

                Box {
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
                            viewModel.addRule(type, pattern, match)
                        },
                        onUpdateRule = { ruleId, pattern, match ->
                            viewModel.updateRule(ruleId, pattern, match)
                        },
                        onDeleteRule = { ruleId ->
                            viewModel.deleteRule(ruleId)
                        },
                        limitType = limitType,
                        hideTitleOption = hideTitle
                    )

                    if (uiState.isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
