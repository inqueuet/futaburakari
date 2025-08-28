package com.valoser.futaburakari.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Headless activity to request media read permissions and return the result.
 * Launch with startActivityForResult or Activity Result API.
 */
class MediaPermissionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requested = intent.getStringArrayExtra(EXTRA_PERMISSIONS)
            ?: PermissionHelper.requiredMediaPermissions()

        val toRequest = requested.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isEmpty()) {
            setResultOk(requested, intArrayOf())
            finish()
            return
        }

        ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), REQ_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE) {
            setResultOk(permissions, grantResults)
            finish()
        }
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

