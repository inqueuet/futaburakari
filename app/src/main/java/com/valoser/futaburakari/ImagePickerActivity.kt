/*
 * 画像選択（ギャラリー/Photo Picker）用アクティビティ。
 * - Android 13以降はPhoto Picker、12L以前はSAFで画像を取得。
 * - 選択した画像URIを編集画面（ImageEditActivity）へ安全に受け渡す。
 */
package com.valoser.futaburakari

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
// import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import com.valoser.futaburakari.ui.theme.LocalSpacing

/**
 * 画像をユーザーに選択させ、編集画面へ受け渡すアクティビティ。
 *
 * 概要:
 * - Android 13 (API 33) 以降: システムの Photo Picker を使用（追加のストレージ権限は不要）。
 * - Android 12L (API 32) 以下: SAF の GetContent を使用（必要に応じて READ_EXTERNAL_STORAGE を要求）。
 * - 挙動: 起動時に自動でピッカーを開き、キャンセル時は本アクティビティを終了。
 * - 受け渡し: 取得した URI に読み取り権限を付与し、`ImageEditActivity` にインテントで渡す。
 */
class ImagePickerActivity : BaseActivity() {

    // Android 13+ の Photo Picker（追加のストレージ権限は不要）
    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            openEditorWithUri(uri)
        } else {
            Toast.makeText(this, "画像選択がキャンセルされました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // Android 12 以下向けのフォールバック（SAF GetContent）
    private val getContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // SAF の URI は呼び出し元に一時的な読み取り権限が付与されるが、次画面へ渡す際は明示的に権限を設定する
            openEditorWithUri(uri)
        } else {
            Toast.makeText(this, "画像選択がキャンセルされました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                launchGallery()
            } else {
                Toast.makeText(this, "ストレージへのアクセス権限が拒否されました", Toast.LENGTH_SHORT).show()
                finish() // 権限がない場合はこの画面を終了
            }
        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            FutaburakariTheme(expressive = true) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = "画像を選択", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                                actionIconContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                ) { inner ->
                    Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(LocalSpacing.current.l),
                            verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.m),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("ギャラリーから画像を選択します")
                            androidx.compose.material3.FilledTonalButton(onClick = { checkAndOpenGallery() }) { Text("画像を選択") }
                        }
                    }
                }
            }
        }

        // 起動時に自動でギャラリー（ピッカー）を開く
        checkAndOpenGallery()
    }

    /**
     * 端末のバージョンに応じて必要な権限を確認し、
     * 問題なければ画像ピッカーを起動する。
     */
    private fun checkAndOpenGallery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Photo Picker はストレージ権限が不要
            launchGallery()
            return
        }

        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.isEmpty()) {
            launchGallery()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    /**
     * 画像ピッカーを起動する。
     * Android 13 以降は Photo Picker、それ以前は SAF の GetContent を使用。
     */
    private fun launchGallery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // システムの Photo Picker を使用
            pickVisualMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } else {
            // SAF の GetContent を使用（Google フォト等との互換のため広く利用可能）
            getContentLauncher.launch("image/*")
        }
    }

    /**
     * 取得した画像 URI を編集画面へ渡す。
     * 読み取り権限を付与し、`ClipData` にも設定して確実に権限を伝播させる。
     */
    private fun openEditorWithUri(it: Uri) {
        // 取得した URI を編集画面に渡す。読み取り権限を付与し、ClipData にも設定して確実に権限伝播させる。
        val intent = Intent(this, ImageEditActivity::class.java).apply {
            data = it
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(ImageEditActivity.EXTRA_IMAGE_URI, it)
            clipData = android.content.ClipData.newUri(contentResolver, "image", it)
        }
        try { grantUriPermission(packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        startActivity(intent)
        finish()
    }
}
