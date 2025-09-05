package com.valoser.futaburakari

import android.content.ContentValues
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.valoser.futaburakari.ui.compose.ImageEditorCanvas
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import dagger.hilt.android.EntryPointAccessors

/**
 * 画像にモザイク/消しゴムを適用できる Compose ベースの簡易編集アクティビティ。
 *
 * 概要:
 * - 入力: インテントの `EXTRA_IMAGE_URI` または `data` に付与された URI。
 * - 読み込み: 画像を非同期にデコードし、`EditingEngine` を生成して `ImageEditorCanvas` で描画/操作。
 * - ツール: モザイク/消しゴムの切替、ブラシ太さ、モザイク強さ、操作ロックの切替を UI で提供。
 * - 保存: 合成結果をギャラリーへ保存。可能なら EXIF の UserComment にプロンプト（説明文）を埋め込む。
 * - 権限/保存先: API に応じて MediaStore/外部ストレージを使い分け、API 28 以下では書込権限を確認。
 */
class ImageEditActivity : BaseActivity() {

    companion object {
        // 編集対象画像のURIを受け取るためのキー
        const val EXTRA_IMAGE_URI = "com.valoser.futaburakari.EXTRA_IMAGE_URI"
        // API 28以下でのWRITE_EXTERNAL_STORAGE権限リクエスト用コード
        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 1001
    }

    private val viewModel: ImageEditViewModel by viewModels()
    // Compose表示トリガー用の状態（Bitmapローディング完了を通知）
    private val editorBitmapState = androidx.compose.runtime.mutableStateOf<Bitmap?>(null)

    // 描画/ジェスチャは ImageEditorCanvas 側で扱う。ここではロック状態のみ保持。
    private var isLocked = false

    private enum class Tool { NONE, MOSAIC, ERASER }
    private var currentTool = Tool.NONE

    private var currentBrushSizePx = 30
    private var currentMosaicAlpha = 255

    private var imageUri: Uri? = null

    // メタデータ抽出（プロンプト取得）に用いるネットワーククライアントを EntryPoint から取得
    private val networkClient: NetworkClient by lazy {
        EntryPointAccessors.fromApplication(applicationContext, NetworkEntryPoint::class.java).networkClient()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_IMAGE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(EXTRA_IMAGE_URI)
        }
        // フォールバック: data に付与された URI を使用
        if (imageUri == null) {
            imageUri = intent.data
        }

        if (imageUri == null) {
            Toast.makeText(this, "画像URIがありません", Toast.LENGTH_LONG).show()
            finish()
            return
        }


        setContent {
            FutaburakariTheme(expressive = true) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = getString(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(inner)
                    ) {
                        // キャンバス領域（ズーム/オーバーレイは Compose で完結）
                        // コントロール用状態（オーバーレイから参照されるため先に定義）
                        var brushSize by rememberSaveable { mutableIntStateOf(currentBrushSizePx) }
                        var mosaicAlpha by rememberSaveable { mutableIntStateOf(currentMosaicAlpha) }
                        var toolName by rememberSaveable { mutableStateOf(currentTool.name) }
                        var locked by rememberSaveable { mutableStateOf(isLocked) }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val bmp = editorBitmapState.value
                            if (bmp != null) {
                                ImageEditorCanvas(
                                    bitmap = bmp,
                                    engine = viewModel.editingEngine,
                                    toolName = toolName,
                                    locked = locked,
                                    brushSizePx = brushSize,
                                    mosaicAlpha = mosaicAlpha,
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // コントロール（Compose）

                        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // ブラシ太さ
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("太さ: ${brushSize}px")
                                    Spacer(Modifier.width(12.dp))
                                    Slider(
                                        value = brushSize.toFloat(),
                                        onValueChange = {
                                            brushSize = it.toInt().coerceIn(1, 50)
                                            currentBrushSizePx = brushSize
                                        },
                                        valueRange = 1f..50f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                // モザイク強さ
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    val pct = (mosaicAlpha / 255f * 100).toInt()
                                    Text("強さ: ${pct}%")
                                    Spacer(Modifier.width(12.dp))
                                    Slider(
                                        value = mosaicAlpha.toFloat(),
                                        onValueChange = {
                                            mosaicAlpha = it.toInt().coerceIn(0, 255)
                                            currentMosaicAlpha = mosaicAlpha
                                            viewModel.editingEngine?.setMosaicAlpha(currentMosaicAlpha)
                                        },
                                        valueRange = 0f..255f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    if (toolName == Tool.MOSAIC.name) {
                                        androidx.compose.material3.FilledTonalButton(onClick = { /* no-op */ }, modifier = Modifier.weight(1f)) { Text("モザイク") }
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedButton(onClick = { applyTool(Tool.ERASER); toolName = Tool.ERASER.name }, modifier = Modifier.weight(1f)) { Text("消しゴム") }
                                    } else if (toolName == Tool.ERASER.name) {
                                        OutlinedButton(onClick = { applyTool(Tool.MOSAIC); toolName = Tool.MOSAIC.name }, modifier = Modifier.weight(1f)) { Text("モザイク") }
                                        Spacer(Modifier.width(8.dp))
                                        androidx.compose.material3.FilledTonalButton(onClick = { /* no-op */ }, modifier = Modifier.weight(1f)) { Text("消しゴム") }
                                    } else {
                                        OutlinedButton(onClick = { applyTool(Tool.MOSAIC); toolName = Tool.MOSAIC.name }, modifier = Modifier.weight(1f)) { Text("モザイク") }
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedButton(onClick = { applyTool(Tool.ERASER); toolName = Tool.ERASER.name }, modifier = Modifier.weight(1f)) { Text("消しゴム") }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = {
                                        locked = !locked
                                        isLocked = locked
                                        // ロック解除時はツールをNONEへ（誤操作防止）
                                        if (!locked) {
                                            applyTool(Tool.NONE)
                                        }
                                    }, modifier = Modifier.weight(1f)) {
                                        Text(if (locked) "解除" else "固定")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { saveImageToGallery() }, modifier = Modifier.weight(1f)) { Text("保存") }
                                }
                            }
                        }
                    }
                }
            }
        }
        // レンダリング/操作は Compose のみで完結

        if (savedInstanceState != null) {
            isLocked = savedInstanceState.getBoolean("lock_state", false)
            // ロックUI状態はComposeの状態で管理
        }

        // 画像読み込みと編集エンジンの構築をバックグラウンドで実行（UI スレッドをブロックしない）
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(imageUri!!).use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    } ?: throw Exception("ビットマップのデコードに失敗")
                }

                val engine = withContext(Dispatchers.Default) {
                    com.valoser.futaburakari.edit.EditingEngine(bmp)
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing) return@withContext
                    viewModel.setPreparedEngine(bmp, engine)
                    editorBitmapState.value = bmp
                    // ImageEditorCanvas はこのBitmapとEngineを直接参照して描画する
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@ImageEditActivity, "画像の読み込みに失敗: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // 現在のツールを切り替える（Compose 側の保存状態初期値にも反映される）
    private fun applyTool(tool: Tool) {
        currentTool = tool
    }

    // 従来の（非 Compose）UI 初期化は不要。保存/ロック/ボタンは Compose で完結。

    @SuppressLint("MissingPermission")
    private fun saveImageToGallery() {
        // 画像をギャラリーへ保存する処理。
        // - API < 29: WRITE_EXTERNAL_STORAGE 権限を事前確認。
        // - API >= 29: MediaStore に相対パス指定で保存（外部ストレージの直接書込不要）。
        // - EXIF: 可能であれば UserComment にプロンプト文字列を埋め込む（読み込みに失敗した場合はスキップ）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_WRITE_EXTERNAL_STORAGE
                )
                return
            }
        }

        val finalBitmap = viewModel.editingEngine?.composeFinal() ?: return
        val fileName = "Edited_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        var imagePathForExif: String? = null
        var imageOutUriForExif: Uri? = null

        CoroutineScope(Dispatchers.Main).launch {
            var prompt: String? = null
            if (imageUri != null) {
                prompt = try {
                    MetadataExtractor.extract(this@ImageEditActivity, imageUri.toString(), networkClient)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "Futaburakari")
                        }
                        val imageOutUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        imageOutUriForExif = imageOutUri
                        fos = imageOutUri?.let { resolver.openOutputStream(it) }
                    } else {
                        @Suppress("DEPRECATION")
                        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + File.separator + "Futaburakari")
                        if (!imagesDir.exists()) {
                            imagesDir.mkdirs()
                        }
                        val imageFile = File(imagesDir, fileName)
                        imagePathForExif = imageFile.absolutePath
                        fos = FileOutputStream(imageFile)
                    }

                    fos?.use {
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    } ?: throw Exception("出力ストリームの取得に失敗")

                    if (prompt != null) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageOutUriForExif != null) {
                                contentResolver.openFileDescriptor(imageOutUriForExif!!, "rw")?.use { pfd ->
                                    val exif = ExifInterface(pfd.fileDescriptor)
                                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, prompt)
                                    exif.saveAttributes()
                                }
                            } else if (imagePathForExif != null) {
                                val exif = ExifInterface(imagePathForExif!!)
                                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, prompt)
                                exif.saveAttributes()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImageEditActivity, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImageEditActivity, "${getString(R.string.save_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveImageToGallery()
            } else {
                Toast.makeText(this, getString(R.string.save_failed) + ": 権限がありません", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // ロック状態のみ保存して復元時に反映
        outState.putBoolean("lock_state", isLocked)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

}
