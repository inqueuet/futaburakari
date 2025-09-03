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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
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

    // Extract prompt/metadata for images in background
    LaunchedEffect(type, url) {
        if (type == "image" && !url.isNullOrBlank() && text.isNullOrBlank()) {
            text = MetadataExtractor.extract(ctx, url, networkClient)
        }
    }

    // CreateDocument launcher for saving text
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

    val canShowTextActions = (type == "text") || (!text.isNullOrBlank())
    val canShowSaveMedia = (type == "image" && onSaveImage != null) || (type == "video" && onSaveVideo != null)

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "戻る") }
                },
                actions = {
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
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "コピー") }

                        IconButton(onClick = {
                            val suggested = buildDefaultFileName(text)
                            createTextFileLauncher.launch("$suggested.txt")
                        }) { Icon(Icons.Default.Save, contentDescription = "テキスト保存") }
                    }
                    if (canShowSaveMedia) {
                        IconButton(onClick = {
                            when (type) {
                                "image" -> if (!url.isNullOrBlank()) onSaveImage?.invoke() else scope.launch { snackbarHostState.showSnackbar("画像URLがありません") }
                                "video" -> if (!url.isNullOrBlank()) onSaveVideo?.invoke() else scope.launch { snackbarHostState.showSnackbar("動画URLがありません") }
                            }
                        }) { Icon(Icons.Default.Save, contentDescription = "メディア保存") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
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
