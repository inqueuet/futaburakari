package com.valoser.futaburakari.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

    /**
     * Returns the set of media permissions needed on this platform version.
     * - API 33+ (Tiramisu): READ_MEDIA_IMAGES and READ_MEDIA_VIDEO
     * - API 32- (S): READ_EXTERNAL_STORAGE
     */
    fun requiredMediaPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 34 -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            }
            Build.VERSION.SDK_INT >= 33 -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                )
            }
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasAllMediaPermissions(context: Context): Boolean =
        missingMediaPermissions(context).isEmpty()

    fun missingMediaPermissions(context: Context): Array<String> =
        requiredMediaPermissions().filterNot { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
}
