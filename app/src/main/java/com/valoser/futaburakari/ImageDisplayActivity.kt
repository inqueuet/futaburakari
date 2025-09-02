package com.valoser.futaburakari

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.text.method.ScrollingMovementMethod
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
// import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import coil.load

class ImageDisplayActivity : BaseActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_PROMPT_INFO = "extra_prompt_info"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_display)

        val imageView: ImageView = findViewById(R.id.imageViewDisplayedImage)
        val textViewPrompt: TextView = findViewById(R.id.textViewPromptInfo)
        // Ensure long prompt content can scroll within its area
        textViewPrompt.movementMethod = ScrollingMovementMethod.getInstance()
        textViewPrompt.isVerticalScrollBarEnabled = true
        val buttonCopy: Button = findViewById(R.id.buttonCopyPrompt)

        val imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        val promptInfo = intent.getStringExtra(EXTRA_PROMPT_INFO)

        if (imageUriString != null) {
            val imageUri = Uri.parse(imageUriString)
            imageView.load(imageUri) // Coilライブラリを使って画像を読み込む
        }

        if (!promptInfo.isNullOrBlank()) {
            textViewPrompt.text = promptInfo
        } else {
            textViewPrompt.text = getString(R.string.prompt_info_not_found)
            buttonCopy.isEnabled = false // プロンプトがない場合はコピーボタンを無効化
        }

        buttonCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("prompt", textViewPrompt.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "プロンプトをコピーしました", Toast.LENGTH_SHORT).show()
        }

        // Adjust bottom margin for the copy button to account for navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(buttonCopy) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val mlp = view.layoutParams as ViewGroup.MarginLayoutParams
            view.updateLayoutParams {
                if (this is ViewGroup.MarginLayoutParams) {
                    bottomMargin = mlp.bottomMargin + insets.bottom
                }
            }
            WindowInsetsCompat.CONSUMED
        }
    }
}
