package com.valoser.futaburakari.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {

    /**
     * このプラットフォームバージョンでアプリが必要とするメディア系パーミッションのセットを返します。
     * - API 34+ (UpsideDownCake 以降): READ_MEDIA_IMAGES, READ_MEDIA_VIDEO,
     *   READ_MEDIA_VISUAL_USER_SELECTED（ユーザー選択メディアの継続アクセス用）
     * - API 33 (Tiramisu): READ_MEDIA_IMAGES, READ_MEDIA_VIDEO
     * - API 32 以下: READ_EXTERNAL_STORAGE
     *
     * 音声ファイルにはアクセスしないため READ_MEDIA_AUDIO は含めていません。
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

    /**
     * すべての必要なメディア系パーミッションが付与されているかどうか。
     */
    fun hasAllMediaPermissions(context: Context): Boolean =
        missingMediaPermissions(context).isEmpty()

    /**
     * 未付与のメディア系パーミッション一覧を返します。
     */
    fun missingMediaPermissions(context: Context): Array<String> =
        requiredMediaPermissions().filterNot { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
}
