package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.valoser.futaburakari.ui.expressive.ExpressiveShowcaseScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

/**
 * 開発用アクティビティ: Material 3 の「Expressive」スタイルを
 * まとめて確認するためのショーケース画面のエントリです。
 *
 * - `FutaburakariTheme(expressive = true)` を適用してコンポーネント/モーション/シェイプを確認できます。
 * - 実運用向けの画面ではありません。リリースビルドでは Manifest から無効化/削除してください。
 * - 構成要素の実装は `ui.expressive` パッケージ（例: `ExpressiveShowcaseScreen`）にあります。
 */
class ExpressiveDemoActivity : ComponentActivity() {
    /**
     * Expressive ショーケースを起動し、テーマを適用した Compose UI を構築する。
     * 実運用向けではなく、開発時の見た目検証用エントリ。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FutaburakariTheme(expressive = true) {
                Surface { ExpressiveShowcaseScreen() }
            }
        }
    }
}
