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
 * メディア読み取り系パーミッションを要求し、結果を呼び出し元へ返すヘッドレス Activity。
 *
 * - 呼び出し側は `startActivityForResult`（レガシー）または Activity Result API の
 *   `StartActivityForResult` で起動し、`RESULT_OK` の `Intent` から結果を受け取ります。
 *   権限が拒否された場合でも `RESULT_OK` で返り、エクストラの内容で判断します。
 * - 要求する権限は `EXTRA_PERMISSIONS` で明示でき、未指定の場合は
 *   `PermissionHelper.requiredMediaPermissions()` の既定セットを使用します。
 * - この Activity 自身は UI を持たず、結果確定後に即座に `finish()` します。
 */
class MediaPermissionActivity : ComponentActivity() {

    private lateinit var requested: Array<String>

    /**
     * `RequestMultiplePermissions` の結果を従来の `onActivityResult` 互換の形
     * （`permissions` と `grantResults` の配列）に整形して返却します。
     */
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

    /**
     * 呼び出し元に `RESULT_OK` とともに結果を返します。
     *
     * - `RESULT_PERMISSIONS`: 対象となったパーミッションの配列。
     * - `RESULT_GRANT_RESULTS`: 各パーミッションの認可結果（`PackageManager.PERMISSION_*`）。
     */
    private fun setResultOk(permissions: Array<out String>, grantResults: IntArray) {
        val data = Intent().apply {
            putExtra(RESULT_PERMISSIONS, permissions)
            putExtra(RESULT_GRANT_RESULTS, grantResults)
        }
        setResult(Activity.RESULT_OK, data)
    }

    companion object {
        /**
         * レガシー実装で利用していたリクエストコードの名残。
         * 現在は参照されていませんが、過去のドキュメントとの整合性のために保持しています。
         */
        private const val REQ_CODE = 1001

        /** 起動時に要求するパーミッション配列を指定するエクストラキー。 */
        const val EXTRA_PERMISSIONS = "com.valoser.futaburakari.extra.PERMISSIONS"
        /** 結果に含まれる、対象パーミッション配列のキー。 */
        const val RESULT_PERMISSIONS = "com.valoser.futaburakari.result.PERMISSIONS"
        /** 結果に含まれる、各パーミッションの認可結果配列のキー。 */
        const val RESULT_GRANT_RESULTS = "com.valoser.futaburakari.result.GRANT_RESULTS"

        /**
         * 本 Activity を起動するための `Intent` を生成します。
         * `permissions` を指定しなかった場合は既定のメディア関連パーミッションを要求します。
         */
        fun intent(context: Context, permissions: Array<String>? = null): Intent =
            Intent(context, MediaPermissionActivity::class.java).apply {
                if (permissions != null) putExtra(EXTRA_PERMISSIONS, permissions)
            }
    }
}
