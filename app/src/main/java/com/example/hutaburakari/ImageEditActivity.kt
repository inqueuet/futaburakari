package com.example.hutaburakari

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface // Added for Exif
import com.example.hutaburakari.edit.EditingEngine
import com.example.hutaburakari.ui.BrushOverlayView
import com.example.hutaburakari.ui.MosaicOverlayView
import com.example.hutaburakari.ui.ZoomImageView
import kotlinx.coroutines.CoroutineScope // Added for Coroutines
import kotlinx.coroutines.Dispatchers // Added for Coroutines
import kotlinx.coroutines.launch // Added for Coroutines
import kotlinx.coroutines.withContext // Added for Coroutines
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class ImageEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "com.example.hutaburakari.EXTRA_IMAGE_URI"
    }

    private lateinit var imageView: ZoomImageView
    private lateinit var brushOverlay: BrushOverlayView
    private lateinit var mosaicOverlay: MosaicOverlayView // Added
    private lateinit var seekBrushSize: SeekBar
    private lateinit var textBrushSizeValue: TextView
    private lateinit var buttonMosaic: Button
    private lateinit var buttonErase: Button
    private lateinit var buttonSave: Button
    private lateinit var buttonLock: Button // Added for lock feature
    private var isLocked = false // Added for lock feature


    private enum class Tool { NONE, MOSAIC, ERASER }
    private var currentTool = Tool.NONE

    private var currentBrushSizePx = 30

    private var sourceBitmap: Bitmap? = null
    private lateinit var editingEngine: EditingEngine // Added
    private var imageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_edit)

        imageView = findViewById(R.id.imageView)
        brushOverlay = findViewById(R.id.brushOverlay)
        mosaicOverlay = findViewById(R.id.mosaicOverlay) // Added
        seekBrushSize = findViewById(R.id.seekBrushSize)
        textBrushSizeValue = findViewById(R.id.textBrushSizeValue)
        buttonMosaic = findViewById(R.id.buttonMosaic)
        buttonErase = findViewById(R.id.buttonErase)
        buttonSave = findViewById(R.id.buttonSave)
        buttonLock = findViewById(R.id.buttonLock) // Added for lock feature


        imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_IMAGE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(EXTRA_IMAGE_URI)
        }

        if (imageUri == null) {
            Toast.makeText(this, "画像URIがありません", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        try {
            contentResolver.openInputStream(imageUri!!).use { inputStream ->
                sourceBitmap = BitmapFactory.decodeStream(inputStream)
            }
            if (sourceBitmap == null) {
                throw Exception("ビットマップのデコードに失敗")
            }
            editingEngine = EditingEngine(sourceBitmap!!) // Changed: Initialize EditingEngine
            imageView.setImageBitmap(sourceBitmap) // Display original bitmap initially

            // Setup MosaicOverlayView
            mosaicOverlay.zoomImageView = imageView
            mosaicOverlay.engine = editingEngine

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "画像の読み込みに失敗: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupBrushSizeControls()
        setupToolButtons()
        setupTouchListener() 
        setupSaveButton()
        setupLockButton() 

        // (任意）回転復元 for lock state
        if (savedInstanceState != null) {
            isLocked = savedInstanceState.getBoolean("lock_state", false)
            imageView.setTransformLocked(isLocked)
            updateLockButtonUI()
        }


        // Forward touch events from overlays to ZoomImageView and invalidate mosaic
        mosaicOverlay.setOnTouchListener { _, ev ->
            imageView.dispatchTouchEvent(ev)
            mosaicOverlay.invalidate() // Invalidate mosaic on touch to reflect zoom/pan
            true
        }
        brushOverlay.setOnTouchListener { _, ev ->
            imageView.dispatchTouchEvent(ev)
            true
        }

        imageView.onMatrixChanged = {
            mosaicOverlay.invalidate()
        }
    }

    private fun setupBrushSizeControls() {
        seekBrushSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSize = progress.coerceIn(1, seekBar?.max ?: 50)
                currentBrushSizePx = newSize
                updateBrushSizeLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekBrushSize.progress = currentBrushSizePx // Initialize seekbar
        updateBrushSizeLabel()
    }

    private fun updateBrushSizeLabel() {
        textBrushSizeValue.text = "${currentBrushSizePx}px"
    }

    private fun setupToolButtons() {
        buttonMosaic.setOnClickListener { applyTool(Tool.MOSAIC) }
        buttonErase.setOnClickListener { applyTool(Tool.ERASER) }
    }

    private fun applyTool(tool: Tool) {
        currentTool = tool
        // 見た目（任意）
        buttonMosaic.isSelected = (tool == Tool.MOSAIC)
        buttonErase.isSelected  = (tool == Tool.ERASER)
        // ツール切替直後はブラシ円を消す（意図しない残像防止）
        brushOverlay.hideCircle()
    }

    // --- Start of NEW setupTouchListener ---
    private fun setupTouchListener() {
        imageView.setOnTouchListener { _, event ->
            // 先にズーム/パン（ZoomImageView内部の検出を動かす）
            val handledZoom = imageView.onTouchEvent(event)

            // ---- ゲート：ロック中 && ツール選択済み && 1本指 のときだけ描画 ----
            val canDraw = isLocked && currentTool != Tool.NONE && event.pointerCount == 1
            if (!canDraw) {
                // ブラシ円は出さない
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    brushOverlay.hideCircle()
                } else {
                    brushOverlay.hideCircle() // Also hide on other actions like move if not drawing
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
                                editingEngine.applyMosaic(p.x, p.y, diameterImagePx)
                                handledDraw = true
                            }
                            Tool.ERASER -> {
                                editingEngine.eraseMosaic(p.x, p.y, diameterImagePx)
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
    // --- End of NEW setupTouchListener ---

    private fun setupSaveButton() {
        buttonSave.setOnClickListener {
            saveImageToGallery()
        }
    }

    // --- Start of NEW setupLockButton ---
    private fun setupLockButton() {
        buttonLock.setOnClickListener {
            isLocked = !isLocked
            imageView.setTransformLocked(isLocked)
            updateLockButtonUI()

            if (!isLocked) {
                applyTool(Tool.NONE)      // ← ロック解除でツール無効化
                brushOverlay.hideCircle()
            }
            mosaicOverlay.invalidate()
        }
    }
    // --- End of NEW setupLockButton ---

    // Added for lock feature
    private fun updateLockButtonUI() {
        buttonLock.text = if (isLocked) "解除" else "固定"
        // ボタンの色など変えたい場合はここで
    }

    // --- Start of NEW saveImageToGallery (with Exif writing) ---
    private fun saveImageToGallery() {
        val finalBitmap = editingEngine.composeFinal()
        val fileName = "Edited_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        var imagePathForExif: String? = null // For pre-Q
        var imageOutUriForExif: Uri? = null // For Q and later

        // プロンプト情報を取得 (非同期処理なので Coroutine を使用)
        CoroutineScope(Dispatchers.Main).launch {
            var prompt: String? = null
            if (imageUri != null) {
                prompt = try {
                    MetadataExtractor.extract(this@ImageEditActivity, imageUri.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                    null // エラー時はnull
                }
            }

            withContext(Dispatchers.IO) { // ファイル操作はIOスレッドで
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "Hutaburakari")
                        }
                        val imageOutUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        imageOutUriForExif = imageOutUri // URIを保持
                        fos = imageOutUri?.let { resolver.openOutputStream(it) }
                    } else {
                        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + File.separator + "Hutaburakari")
                        if (!imagesDir.exists()) {
                            imagesDir.mkdirs()
                        }
                        val imageFile = File(imagesDir, fileName)
                        imagePathForExif = imageFile.absolutePath // パスを保持
                        fos = FileOutputStream(imageFile)
                    }

                    fos?.use {
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    } ?: throw Exception("出力ストリームの取得に失敗")

                    // --- プロンプト情報の書き込み ---
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
                            // Exif書き込み失敗はトースト表示しないか、別途ログに記録
                        }
                    }
                    // --- プロンプト情報の書き込みここまで ---

                    // UIスレッドでトースト表示
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImageEditActivity, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    // UIスレッドでトースト表示
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ImageEditActivity, "${getString(R.string.save_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    // --- End of NEW saveImageToGallery ---

    // Added for lock feature (rotation persistence)
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("lock_state", isLocked)
    }

    override fun onDestroy() {
        super.onDestroy()
        sourceBitmap?.recycle()
        // editingBitmap is no longer directly managed here, EditingEngine handles its own bitmaps
    }
}
