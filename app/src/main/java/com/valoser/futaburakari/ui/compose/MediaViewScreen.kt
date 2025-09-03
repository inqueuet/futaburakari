package com.valoser.futaburakari.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import com.valoser.futaburakari.widget.ZoomableImageView
import com.valoser.futaburakari.MetadataExtractor
import com.valoser.futaburakari.NetworkClient

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
                    android.widget.Toast.makeText(ctx, "テキストを保存しました", android.widget.Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    android.widget.Toast.makeText(ctx, "テキストの保存に失敗しました: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                android.widget.Toast.makeText(ctx, "保存するテキストがありません", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val canShowTextActions = (type == "text") || (!text.isNullOrBlank())
    val canShowSaveMedia = (type == "image" && onSaveImage != null) || (type == "video" && onSaveVideo != null)

    Scaffold(
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
                                android.widget.Toast.makeText(ctx, "テキストをコピーしました", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(ctx, "コピーするテキストがありません", android.widget.Toast.LENGTH_SHORT).show()
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
                                "image" -> onSaveImage?.invoke()
                                "video" -> onSaveVideo?.invoke()
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
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ZoomableImageView(ctx).apply {
                        minScale = 1.0f
                        midScale = 2.5f
                        maxScale = 5.0f
                        if (!url.isNullOrBlank()) {
                            this.load(url)
                        }
                    }
                },
                update = { iv ->
                    if (!url.isNullOrBlank()) {
                        iv.load(url)
                    }
                }
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
