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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.example.hutaburakari.edit.EditingEngine
import com.example.hutaburakari.ui.BrushOverlayView
import com.example.hutaburakari.ui.MosaicOverlayView
import com.example.hutaburakari.ui.ZoomImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class ImageEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "com.example.hutaburakari.EXTRA_IMAGE_URI"
    }

    private val viewModel: ImageEditViewModel by viewModels()

    private lateinit var imageView: ZoomImageView
    private lateinit var brushOverlay: BrushOverlayView
    private lateinit var mosaicOverlay: MosaicOverlayView
    private lateinit var seekBrushSize: SeekBar
    private lateinit var textBrushSizeValue: TextView
    private lateinit var seekMosaicAlpha: SeekBar
    private lateinit var textMosaicAlphaValue: TextView
    private lateinit var buttonMosaic: Button
    private lateinit var buttonErase: Button
    private lateinit var buttonSave: Button
    private lateinit var buttonLock: Button
    private var isLocked = false

    private enum class Tool { NONE, MOSAIC, ERASER }
    private var currentTool = Tool.NONE

    private var currentBrushSizePx = 30
    private var currentMosaicAlpha = 255

    private var imageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_edit)

        // (findViewByIdは変更なし)
        imageView = findViewById(R.id.imageView)
        brushOverlay = findViewById(R.id.brushOverlay)
        mosaicOverlay = findViewById(R.id.mosaicOverlay)
        seekBrushSize = findViewById(R.id.seekBrushSize)
        textBrushSizeValue = findViewById(R.id.textBrushSizeValue)
        seekMosaicAlpha = findViewById(R.id.seekMosaicAlpha)
        textMosaicAlphaValue = findViewById(R.id.textMosaicAlphaValue)
        buttonMosaic = findViewById(R.id.buttonMosaic)
        buttonErase = findViewById(R.id.buttonErase)
        buttonSave = findViewById(R.id.buttonSave)
        buttonLock = findViewById(R.id.buttonLock)


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
            if (viewModel.editingEngine == null) {
                val sourceBitmap: Bitmap? = contentResolver.openInputStream(imageUri!!).use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
                if (sourceBitmap == null) {
                    throw Exception("ビットマップのデコードに失敗")
                }
                viewModel.initializeEngine(sourceBitmap)
            }

            // ★★★ ここを修正 ★★★
            // editingEngineの中ではなく、ViewModelが直接持つsourceBitmapを参照する
            imageView.setImageBitmap(viewModel.sourceBitmap!!)
            mosaicOverlay.zoomImageView = imageView
            mosaicOverlay.engine = viewModel.editingEngine!!

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "画像の読み込みに失敗: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // (setupメソッド群の呼び出しは変更なし)
        setupBrushSizeControls()
        setupMosaicAlphaControls()
        setupToolButtons()
        setupTouchListener()
        setupSaveButton()
        setupLockButton()

        if (savedInstanceState != null) {
            isLocked = savedInstanceState.getBoolean("lock_state", false)
            imageView.setTransformLocked(isLocked)
            updateLockButtonUI()
        }

        mosaicOverlay.setOnTouchListener { _, ev ->
            imageView.dispatchTouchEvent(ev)
            mosaicOverlay.invalidate()
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

    // (これ以降のメソッドは変更ありません)
    // ...
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
        seekBrushSize.progress = currentBrushSizePx
        updateBrushSizeLabel()
    }

    private fun updateBrushSizeLabel() {
        textBrushSizeValue.text = "${currentBrushSizePx}px"
    }

    private fun setupMosaicAlphaControls() {
        seekMosaicAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentMosaicAlpha = progress.coerceIn(0, 255)
                viewModel.editingEngine?.setMosaicAlpha(currentMosaicAlpha)
                updateMosaicAlphaLabel()
                mosaicOverlay.invalidateLayer()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        seekMosaicAlpha.progress = currentMosaicAlpha
        updateMosaicAlphaLabel()
    }

    private fun updateMosaicAlphaLabel() {
        val percentage = (currentMosaicAlpha / 255.0 * 100).toInt()
        textMosaicAlphaValue.text = "${percentage}%"
    }

    private fun setupToolButtons() {
        buttonMosaic.setOnClickListener { applyTool(Tool.MOSAIC) }
        buttonErase.setOnClickListener { applyTool(Tool.ERASER) }
    }

    private fun applyTool(tool: Tool) {
        currentTool = tool
        buttonMosaic.isSelected = (tool == Tool.MOSAIC)
        buttonErase.isSelected  = (tool == Tool.ERASER)
        brushOverlay.hideCircle()
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

    private fun setupSaveButton() {
        buttonSave.setOnClickListener {
            saveImageToGallery()
        }
    }

    private fun setupLockButton() {
        buttonLock.setOnClickListener {
            isLocked = !isLocked
            imageView.setTransformLocked(isLocked)
            updateLockButtonUI()

            if (!isLocked) {
                applyTool(Tool.NONE)
                brushOverlay.hideCircle()
            }
            mosaicOverlay.invalidate()
        }
    }

    private fun updateLockButtonUI() {
        buttonLock.text = if (isLocked) "解除" else "固定"
    }

    private fun saveImageToGallery() {
        val finalBitmap = viewModel.editingEngine?.composeFinal() ?: return
        val fileName = "Edited_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        var imagePathForExif: String? = null
        var imageOutUriForExif: Uri? = null

        CoroutineScope(Dispatchers.Main).launch {
            var prompt: String? = null
            if (imageUri != null) {
                prompt = try {
                    MetadataExtractor.extract(this@ImageEditActivity, imageUri.toString())
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
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "Hutaburakari")
                        }
                        val imageOutUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        imageOutUriForExif = imageOutUri
                        fos = imageOutUri?.let { resolver.openOutputStream(it) }
                    } else {
                        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + File.separator + "Hutaburakari")
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("lock_state", isLocked)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}