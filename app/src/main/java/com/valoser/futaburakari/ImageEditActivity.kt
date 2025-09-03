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
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.valoser.futaburakari.ui.BrushOverlayView
import com.valoser.futaburakari.ui.MosaicOverlayView
import com.valoser.futaburakari.ui.ZoomImageView
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

class ImageEditActivity : BaseActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "com.valoser.futaburakari.EXTRA_IMAGE_URI"
        private const val REQUEST_WRITE_EXTERNAL_STORAGE = 1001
    }

    private val viewModel: ImageEditViewModel by viewModels()

    private lateinit var imageView: ZoomImageView
    private lateinit var brushOverlay: BrushOverlayView
    private lateinit var mosaicOverlay: MosaicOverlayView
    private var isLocked = false

    private enum class Tool { NONE, MOSAIC, ERASER }
    private var currentTool = Tool.NONE

    private var currentBrushSizePx = 30
    private var currentMosaicAlpha = 255

    private var imageUri: Uri? = null

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

        val colorModePref = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = getString(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                ) { inner ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(inner)
                    ) {
                        // Canvas (Zoom + Overlays)
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            factory = { ctx ->
                                // Create layered container programmatically
                                android.widget.FrameLayout(ctx).apply {
                                    layoutParams = android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                    imageView = ZoomImageView(ctx)
                                    mosaicOverlay = MosaicOverlayView(ctx)
                                    brushOverlay = BrushOverlayView(ctx)

                                    addView(imageView, android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                    ))
                                    addView(mosaicOverlay, android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                    ))
                                    addView(brushOverlay, android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                    ))

                                    // 重い処理（デコード/エンジン生成）は後続のコルーチンで実行
                                    // ここではビューのセットアップとタッチ委譲のみ行う
                                    setupTouchListener()
                                    imageView.onMatrixChanged = { mosaicOverlay.invalidate() }
                                    mosaicOverlay.setOnTouchListener { _, ev ->
                                        imageView.dispatchTouchEvent(ev)
                                        mosaicOverlay.invalidate()
                                        true
                                    }
                                    brushOverlay.setOnTouchListener { _, ev ->
                                        imageView.dispatchTouchEvent(ev)
                                        true
                                    }
                                }
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        // Controls (Compose)
                        var brushSize by rememberSaveable { mutableIntStateOf(currentBrushSizePx) }
                        var mosaicAlpha by rememberSaveable { mutableIntStateOf(currentMosaicAlpha) }
                        var toolName by rememberSaveable { mutableStateOf(currentTool.name) }
                        var locked by rememberSaveable { mutableStateOf(isLocked) }

                        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // Brush size
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

                                // Mosaic alpha
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
                                            mosaicOverlay.invalidateLayer()
                                        },
                                        valueRange = 0f..255f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth()) {
                                    if (toolName == Tool.MOSAIC.name) {
                                        Button(onClick = { /* no-op */ }, modifier = Modifier.weight(1f)) { Text("モザイク") }
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedButton(onClick = { applyTool(Tool.ERASER); toolName = Tool.ERASER.name }, modifier = Modifier.weight(1f)) { Text("消しゴム") }
                                    } else if (toolName == Tool.ERASER.name) {
                                        OutlinedButton(onClick = { applyTool(Tool.MOSAIC); toolName = Tool.MOSAIC.name }, modifier = Modifier.weight(1f)) { Text("モザイク") }
                                        Spacer(Modifier.width(8.dp))
                                        Button(onClick = { /* no-op */ }, modifier = Modifier.weight(1f)) { Text("消しゴム") }
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
                                        imageView.setTransformLocked(locked)
                                        if (!locked) {
                                            applyTool(Tool.NONE)
                                            brushOverlay.hideCircle()
                                        }
                                        mosaicOverlay.invalidate()
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
        // setup methods are invoked inside AndroidView factory

        if (savedInstanceState != null) {
            isLocked = savedInstanceState.getBoolean("lock_state", false)
            if (::imageView.isInitialized) {
                imageView.setTransformLocked(isLocked)
            }
            // Compose controls handle lock UI state
        }

        // 画像読み込みと編集エンジンの構築をバックグラウンドで
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
                    if (::imageView.isInitialized) {
                        imageView.setImageBitmap(viewModel.sourceBitmap!!)
                    }
                    if (::mosaicOverlay.isInitialized) {
                        mosaicOverlay.zoomImageView = imageView
                        mosaicOverlay.engine = viewModel.editingEngine
                        mosaicOverlay.invalidate()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@ImageEditActivity, "画像の読み込みに失敗: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun applyTool(tool: Tool) {
        currentTool = tool
        if (::brushOverlay.isInitialized) brushOverlay.hideCircle()
    }


    private fun setupTouchListener() {
        imageView.setOnTouchListener { _, event ->
            val handledZoom = imageView.onTouchEvent(event)
            val canDraw = isLocked && currentTool != Tool.NONE && event.pointerCount == 1
            if (!canDraw) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    brushOverlay.hideCircle()
                } else {
                    brushOverlay.hideCircle()
                }
                return@setOnTouchListener handledZoom
            }

            var handledDraw = false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    imageView.viewPointToImage(event.x, event.y)?.let { p ->
                        val diameterImagePx = currentBrushSizePx.toFloat()
                        when (currentTool) {
                            Tool.MOSAIC -> {
                                viewModel.editingEngine?.applyMosaic(p.x, p.y, diameterImagePx)
                                handledDraw = true
                            }
                            Tool.ERASER -> {
                                viewModel.editingEngine?.eraseMosaic(p.x, p.y, diameterImagePx)
                                handledDraw = true
                            }
                            else -> {}
                        }
                        mosaicOverlay.invalidateLayer()
                        val radiusViewPx = imageView.imageLengthToView(diameterImagePx) / 2f
                        brushOverlay.showCircle(event.x, event.y, radiusViewPx)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    brushOverlay.hideCircle()
                }
            }
            handledZoom || handledDraw
        }
    }

    // Legacy save/lock/button setup methods removed (Compose handles UI)

    @SuppressLint("MissingPermission")
    private fun saveImageToGallery() {
        // On API < 29, ensure WRITE_EXTERNAL_STORAGE is granted before saving
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
        outState.putBoolean("lock_state", isLocked)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

}
