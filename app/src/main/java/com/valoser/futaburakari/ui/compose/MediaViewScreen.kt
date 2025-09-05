package com.valoser.futaburakari.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.valoser.futaburakari.MetadataExtractor
import com.valoser.futaburakari.NetworkClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * 画像/動画/テキストを表示する汎用メディア画面。
 *
 * 機能概要:
 * - `type == image`: ピンチ/ダブルタップズーム対応の画像ビュー。
 * - `type == video`: ExoPlayer による動画再生ビュー（ライフサイクルで開放）。
 * - それ以外: スクロール可能なテキストビュー。
 * - テキスト（プロンプト）が利用可能ならトップバーからコピー/保存アクションを表示。
 * - 画像時は必要に応じてメタデータ抽出（`MetadataExtractor`）で `text` を補完。
 * - 画像/動画の保存アクションはコールバック指定時のみ表示（URL が空の場合はスナックバー通知）。
 *
 * パラメータ:
 * - `title`: 上部タイトル。
 * - `type`: メディア種別（"image"/"video"/その他）。
 * - `url`: メディアの URL（テキスト時は未使用）。
 * - `initialText`: 初期テキスト（image の場合は抽出で上書き補完される場合あり）。
 * - `networkClient`: メタデータ抽出で利用するネットワーククライアント。
 * - `onBack`: 戻る押下時のハンドラ。
 * - `onSaveImage`/`onSaveVideo`: 保存アクションのハンドラ（指定時のみ表示）。
 */
fun MediaViewScreen(
    title: String,
    type: String,
    url: String?,
    initialText: String?,
    networkClient: NetworkClient,
    onBack: () -> Unit,
    onSaveImage: (() -> Unit)? = null,
    onSaveVideo: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }

    // 画像の場合、プロンプト/メタデータをバックグラウンドで抽出して `text` を補完
    LaunchedEffect(type, url) {
        if (type == "image" && !url.isNullOrBlank() && text.isNullOrBlank()) {
            text = MetadataExtractor.extract(ctx, url, networkClient)
        }
    }

    // テキスト保存用の CreateDocument ランチャー
    val createTextFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let { outUri ->
            val t = text ?: ""
            if (t.isNotEmpty()) {
                runCatching {
                    ctx.contentResolver.openOutputStream(outUri)?.use { os ->
                        os.write(t.toByteArray())
                    }
                    scope.launch { snackbarHostState.showSnackbar("テキストを保存しました") }
                }.onFailure { e ->
                    scope.launch { snackbarHostState.showSnackbar("テキストの保存に失敗しました: ${e.message}") }
                }
            } else {
                scope.launch { snackbarHostState.showSnackbar("保存するテキストがありません") }
            }
        }
    }

    // テキスト操作（コピー/保存）を表示できるか: テキスト画面 or メタデータ取得済み
    val canShowTextActions = (type == "text") || (!text.isNullOrBlank())
    // メディア保存ボタンを表示できるか: 対応タイプ かつ コールバックが提供されている
    val canShowSaveMedia = (type == "image" && onSaveImage != null) || (type == "video" && onSaveVideo != null)

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = {
                    // テキストがある場合はコピー/保存アクションを表示
                    if (canShowTextActions) {
                        IconButton(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val content = text ?: ""
                            if (content.isNotEmpty()) {
                                cm.setPrimaryClip(ClipData.newPlainText("text", content))
                                scope.launch { snackbarHostState.showSnackbar("テキストをコピーしました") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("コピーするテキストがありません") }
                            }
                        }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "コピー") }

                        IconButton(onClick = {
                            val suggested = buildDefaultFileName(text)
                            createTextFileLauncher.launch("$suggested.txt")
                        }) { Icon(Icons.Rounded.Save, contentDescription = "テキスト保存") }
                    }
                    // 対応するメディアの保存（URL が空ならスナックバーで通知）
                    if (canShowSaveMedia) {
                        IconButton(onClick = {
                            when (type) {
                                "image" -> if (!url.isNullOrBlank()) onSaveImage?.invoke() else scope.launch { snackbarHostState.showSnackbar("画像URLがありません") }
                                "video" -> if (!url.isNullOrBlank()) onSaveVideo?.invoke() else scope.launch { snackbarHostState.showSnackbar("動画URLがありません") }
                            }
                        }) { Icon(Icons.Rounded.Save, contentDescription = "メディア保存") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { inner ->
        when (type) {
            "image" -> ImageContent(url = url, modifier = Modifier.fillMaxSize().padding(inner))
            "video" -> VideoContent(url = url, modifier = Modifier.fillMaxSize().padding(inner))
            else -> TextContent(text = text ?: "", modifier = Modifier.fillMaxSize().padding(inner))
        }
    }
}

@Composable
/**
 * ズーム可能な画像表示。1:1 のエリアに収め、ピンチやダブルタップで拡大縮小可。
 */
private fun ImageContent(url: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(12.dp)) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            ZoomableAsyncImage(
                model = url,
                modifier = Modifier.fillMaxSize(),
                minScale = 1f,
                maxScale = 5f,
            )
        }
    }
}

@Composable
/**
 * ExoPlayer を用いた動画表示。URL から `MediaItem` をセットして再生準備。
 * `DisposableEffect` でライフサイクルに合わせてプレイヤーを解放する。
 */
private fun VideoContent(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var player: ExoPlayer? by remember { mutableStateOf(null) }

    DisposableEffect(url) {
        val exo = ExoPlayer.Builder(context).build().also { p ->
            val mediaItem = url?.let { MediaItem.fromUri(Uri.parse(it)) }
            if (mediaItem != null) {
                p.setMediaItem(mediaItem)
                p.prepare()
            }
        }
        player = exo
        onDispose {
            exo.release()
            player = null
        }
    }

    Column(modifier = modifier.padding(0.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                }
            },
            update = { view -> view.player = player }
        )
    }
}

@Composable
/**
 * スクロール可能なテキスト表示。
 */
private fun TextContent(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        )
        Spacer(modifier = Modifier.padding(4.dp))
    }
}

private fun buildDefaultFileName(text: String?): String {
    val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
    val timestamp = sdf.format(java.util.Date())
    val textHint = (text ?: "text").take(15).replace(Regex("[^a-zA-Z0-9_]"), "_")
    return "${textHint}_$timestamp"
}

@Composable
/**
 * ピンチ/ドラッグ/ダブルタップに対応したズーム可能な画像コンポーネント。
 * - ピンチで倍率を `minScale..maxScale` にクランプ。
 * - ドラッグでパン（必要に応じてクランプ可能だが本実装では無制限）。
 * - ダブルタップで中間倍率と 1x をトグルし、オフセットをリセット。
 */
private fun ZoomableAsyncImage(
    model: Any?,
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 5f
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
        scale = newScale
        // パンは必要に応じてクランプ可能（ここでは無制限）
        offset += panChange
    }

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )
            .transformable(state)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // シンプルなトグルズーム
                        val mid = (minScale + maxScale) / 2f
                        scale = if (scale < mid) mid else 1f
                        offset = Offset.Zero
                    }
                )
            }
    )
}
