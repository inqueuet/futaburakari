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
import androidx.activity.result.contract.ActivityResultContracts
// import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.valoser.futaburakari.databinding.ActivityImagePickerBinding

class ImagePickerActivity : BaseActivity() {

    private lateinit var binding: ActivityImagePickerBinding

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imageUri: Uri? = result.data?.data
            imageUri?.let {
                val intent = Intent(this, ImageEditActivity::class.java).apply {
                    putExtra(ImageEditActivity.EXTRA_IMAGE_URI, it) // it (Uri) を直接渡す
                }
                startActivity(intent)
                finish()
            }
        } else {
            // Handle the case where image picking was cancelled or failed
            Toast.makeText(this, "画像選択がキャンセルされました", Toast.LENGTH_SHORT).show()
            finish() // Finish if no image is picked
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                launchGallery()
            } else {
                Toast.makeText(this, "ストレージへのアクセス権限が拒否されました", Toast.LENGTH_SHORT).show()
                finish() // Finish if permission is denied
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonPickImage.setOnClickListener {
            checkAndOpenGallery()
        }

        // Automatically try to open the gallery when the activity starts
        checkAndOpenGallery()
    }

    private fun checkAndOpenGallery() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else { // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            launchGallery()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun launchGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }
}
