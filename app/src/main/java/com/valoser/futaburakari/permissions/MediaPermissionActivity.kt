package com.valoser.futaburakari.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Headless activity to request media read permissions and return the result.
 * Launch with startActivityForResult or Activity Result API.
 */
class MediaPermissionActivity : ComponentActivity() {

    private lateinit var requested: Array<String>

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val permissions = requested
            val grantResults = IntArray(permissions.size) { idx ->
                if (result[permissions[idx]] == true) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
            setResultOk(permissions, grantResults)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requested = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
            ?: PermissionHelper.requiredMediaPermissions()

        val toRequest = requested.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isEmpty()) {
            // All granted already
            setResultOk(requested, IntArray(requested.size) { PackageManager.PERMISSION_GRANTED })
            finish()
            return
        }

        permissionLauncher.launch(toRequest.toTypedArray())
    }

    private fun setResultOk(permissions: Array<out String>, grantResults: IntArray) {
        val data = Intent().apply {
            putExtra(RESULT_PERMISSIONS, permissions)
            putExtra(RESULT_GRANT_RESULTS, grantResults)
        }
        setResult(Activity.RESULT_OK, data)
    }

    companion object {
        private const val REQ_CODE = 1001

        const val EXTRA_PERMISSIONS = "com.valoser.futaburakari.extra.PERMISSIONS"
        const val RESULT_PERMISSIONS = "com.valoser.futaburakari.result.PERMISSIONS"
        const val RESULT_GRANT_RESULTS = "com.valoser.futaburakari.result.GRANT_RESULTS"

        fun intent(context: Context, permissions: Array<String>? = null): Intent =
            Intent(context, MediaPermissionActivity::class.java).apply {
                if (permissions != null) putExtra(EXTRA_PERMISSIONS, permissions)
            }
    }
}

