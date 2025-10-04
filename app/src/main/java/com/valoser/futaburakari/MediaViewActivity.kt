/*
 * 画像・動画・テキストの表示を行うメディアビューア。
 * - 保存操作（API 28以下は書込権限の確認）や戻る操作を提供。
 * - 保存処理は MediaSaver に委譲し、共有の NetworkClient でリモート取得にも対応。
 */
package com.valoser.futaburakari

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.valoser.futaburakari.ui.compose.MediaViewScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

class MediaViewActivity : BaseActivity() {

    private var currentType: String? = null
    private var currentUrl: String? = null
    private var currentText: String? = null // テキスト用（画像メタ/明示テキスト）

    // ネットワークアクセス（表示/保存時の取得等）に利用するクライアントを EntryPoint から取得
    private val networkClient: NetworkClient by lazy {
        EntryPointAccessors.fromApplication(applicationContext, NetworkEntryPoint::class.java).networkClient()
    }

    /**
     * 画面起動時に受け取るパラメータ定義。
     */
    companion object {
        // 表示種別: 画像/動画/テキスト
        const val EXTRA_TYPE = "EXTRA_TYPE"
        // 表示/保存対象のURL/URI（http(s)/content/file など、画像/動画の場合）
        const val EXTRA_URL = "EXTRA_URL"
        // テキスト表示用の内容（プロンプト等）
        const val EXTRA_TEXT = "EXTRA_TEXT"
        // メディア取得時の Referer（通常はスレの res/*.htm）
        const val EXTRA_REFERER = "EXTRA_REFERER"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_TEXT = "text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentType = intent.getStringExtra(EXTRA_TYPE)
        currentUrl = intent.getStringExtra(EXTRA_URL)
        currentText = intent.getStringExtra(EXTRA_TEXT)
        val referer = intent.getStringExtra(EXTRA_REFERER)

        // テーマ適用済みのコンテンツを構築（表現的なカラースキーム）

        setContent {
            FutaburakariTheme(expressive = true) {
                val title = when (currentType) {
                    TYPE_IMAGE -> "画像ビューア"
                    TYPE_VIDEO -> "動画ビューア"
                    TYPE_TEXT -> "テキスト"
                    else -> getString(R.string.app_name)
                }
                // Composeのメディア表示画面へ必要情報とコールバックを渡す
                MediaViewScreen(
                    title = title,
                    type = currentType ?: TYPE_TEXT,
                    url = currentUrl,
                    initialText = currentText,
                    networkClient = networkClient,
                    referer = referer,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onSaveImage = if (currentType == TYPE_IMAGE) ({ saveImage() }) else null,
                    onSaveVideo = if (currentType == TYPE_VIDEO) ({ saveVideo() }) else null
                )
            }
        }
    }

    /**
     * 表示中の画像を端末へ保存する。
     * API < 29（Android 9以下）では事前に WRITE_EXTERNAL_STORAGE 権限を確認し、未許可ならリクエストして終了する。
     * URL/URI は http(s)/content/file いずれにも対応する。
     */
    private fun saveImage() {
        // API < 29 では WRITE_EXTERNAL_STORAGE 権限が必要
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    2001
                )
                return
            }
        }
        // URL/URI が存在する場合のみ MediaSaver で保存
        currentUrl?.let { url ->
            lifecycleScope.launch {
                MediaSaver.saveImage(this@MediaViewActivity, url, networkClient)
            }
        }
    }

    /**
     * 表示中の動画を端末へ保存する。
     * API < 29（Android 9以下）では事前に WRITE_EXTERNAL_STORAGE 権限を確認し、未許可ならリクエストして終了する。
     * URL/URI は http(s)/content/file いずれにも対応する。
     */
    private fun saveVideo() {
        // API < 29 では WRITE_EXTERNAL_STORAGE 権限が必要
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    2001
                )
                return
            }
        }
        // URL/URI が存在する場合のみ MediaSaver で保存
        currentUrl?.let { url ->
            lifecycleScope.launch {
                MediaSaver.saveVideo(this@MediaViewActivity, url, networkClient)
            }
        }
    }
}
