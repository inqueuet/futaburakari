package com.valoser.futaburakari.videoeditor.presentation.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import com.valoser.futaburakari.videoeditor.domain.model.EditorIntent
import com.valoser.futaburakari.videoeditor.presentation.ui.editor.EditorScreen
import com.valoser.futaburakari.videoeditor.presentation.viewmodel.EditorViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * 動画編集アクティビティ
 * - 動画選択
 * - 編集画面の表示
 */
@AndroidEntryPoint
class EditorActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    // 動画選択ランチャー（複数選択対応）
    private val pickVideosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 選択された動画でセッションを作成
            viewModel.handleIntent(EditorIntent.CreateSession(uris))
        } else {
            Toast.makeText(this, "動画が選択されませんでした", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // パーミッション要求ランチャー
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchVideoPicker()
        } else {
            Toast.makeText(
                this,
                "動画にアクセスする権限が必要です",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FutaburakariTheme(expressive = true) {
                val state by viewModel.state.collectAsState()

                when {
                    state.session == null && !state.isLoading -> {
                        // セッションがない場合は動画選択画面
                        VideoSelectionScreen(
                            onSelectVideos = {
                                checkPermissionAndPick()
                            },
                            onBack = {
                                finish()
                            }
                        )
                    }
                    state.isLoading -> {
                        // ローディング画面
                        LoadingScreen(progress = state.exportProgress)
                    }
                    else -> {
                        // 編集画面
                        EditorScreen(
                            viewModel = viewModel,
                            onBack = {
                                finish()
                            }
                        )
                    }
                }

                // エラー表示
                state.error?.let { error ->
                    LaunchedEffect(error) {
                        Toast.makeText(
                            this@EditorActivity,
                            error,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * パーミッションチェックと動画選択
     */
    private fun checkPermissionAndPick() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13以降：READ_MEDIA_VIDEO
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_VIDEO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    launchVideoPicker()
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                }
            }
            else -> {
                // Android 12以前：READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    launchVideoPicker()
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    /**
     * 動画選択ピッカーを起動
     */
    private fun launchVideoPicker() {
        pickVideosLauncher.launch("video/*")
    }
}

/**
 * 動画選択画面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSelectionScreen(
    onSelectVideos: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("動画を選択") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("<")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "編集する動画を選択してください",
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = onSelectVideos,
                    modifier = Modifier
                        .width(200.dp)
                        .height(56.dp)
                ) {
                    Text("動画を選択")
                }

                Text(
                    text = "※複数の動画を選択できます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * ローディング画面
 */
@Composable
fun LoadingScreen(progress: Float? = null) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val clampedProgress = progress?.coerceIn(0f, 100f)
            if (clampedProgress != null) {
                CircularProgressIndicator(progress = clampedProgress / 100f)
                Text("エクスポート中... ${clampedProgress.roundToInt()}%")
            } else {
                CircularProgressIndicator()
                Text("読み込み中...")
            }
        }
    }
}
