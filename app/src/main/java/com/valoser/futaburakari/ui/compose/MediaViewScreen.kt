/**
 * 画像/動画/テキストを共通UIで表示するメディアビューのCompose実装。
 * - 画像のズーム、動画再生、テキストのコピー/保存などを提供します。
 * - テキストは必要に応じてメタデータ抽出で補完し、メディア保存アクションも制御します。
 */
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import coil3.compose.AsyncImage
import coil3.request.transitionFactory
import coil3.transition.CrossfadeTransition
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import com.valoser.futaburakari.image.ImageKeys
import com.valoser.futaburakari.MetadataExtractor
import com.valoser.futaburakari.PromptSettings
import com.valoser.futaburakari.NetworkClient
import kotlinx.coroutines.launch
import com.valoser.futaburakari.ui.theme.LocalSpacing

/**
 * 画像/動画/テキストを表示する汎用メディア画面。
 *
 * 機能概要:
 * - `type == image`: ピンチ/ダブルタップでズーム可能な画像ビュー。
 * - `type == video`: ExoPlayer による動画再生ビュー（ライフサイクルで安全に解放）。
 * - 上記以外: スクロール可能なテキストビュー。
 * - テキスト（プロンプト）があればトップバーからコピー/保存が可能。
 * - 画像時は必要に応じて `MetadataExtractor` により `text` を補完。
 * - 画像/動画の保存はコールバックが指定されている場合のみ表示（URL が空のときはスナックバーで通知）。
 *
 * @param title 上部タイトル。
 * @param type メディア種別（"image"/"video"/その他）。
 * @param url メディアの URL（テキスト時は未使用）。
 * @param initialText 初期テキスト（image の場合は抽出により上書き補完される場合あり）。
 * @param networkClient メタデータ抽出に利用するネットワーククライアント。
 * @param referer 画像取得時に付与する Referer ヘッダ（必要な場合）。
 * @param onBack 戻る押下時のハンドラ。
 * @param onSaveImage 画像保存アクションのハンドラ（指定時のみ表示）。
 * @param onSaveVideo 動画保存アクションのハンドラ（指定時のみ表示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewScreen(
    title: String,
    type: String,
    url: String?,
    initialText: String?,
    networkClient: NetworkClient,
    referer: String? = null,
    onBack: () -> Unit,
    onSaveImage: (() -> Unit)? = null,
    onSaveVideo: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }

    // 画像の場合、プロンプト/メタデータを非同期に抽出して `text` を補完
    LaunchedEffect(type, url) {
        if (
            type == "image" &&
            !url.isNullOrBlank() &&
            text.isNullOrBlank() &&
            PromptSettings.isPromptFetchEnabled(ctx)
        ) {
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
            "image" -> ImageContent(url = url, referer = referer, modifier = Modifier.fillMaxSize().padding(inner))
            "video" -> VideoContent(url = url, modifier = Modifier.fillMaxSize().padding(inner))
            else -> TextContent(text = text ?: "", modifier = Modifier.fillMaxSize().padding(inner))
        }
    }
}

/**
 * ズーム可能な画像表示。画面全体を使い ContentScale.Fit で縦横比を保ったまま描画する。
 */
@Composable
private fun ImageContent(url: String?, referer: String? = null, modifier: Modifier = Modifier) {
    // フルスクリーン領域で中央配置し、ContentScale.Fit で短辺が必ず画面に接するように表示
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ctx = LocalContext.current
        val model = if (!url.isNullOrBlank()) {
            coil3.request.ImageRequest.Builder(ctx)
                .data(url)
                .apply {
                    val builder = NetworkHeaders.Builder()
                        .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                    val ref = referer
                    if (!ref.isNullOrBlank()) builder.add("Referer", ref)
                    httpHeaders(builder.build())
                }
                // 既存のフルサイズ画像キャッシュをプレースホルダーとして使い、表示切替のちらつきを抑える
                .memoryCacheKey(ImageKeys.full(url))
                .placeholderMemoryCacheKey(ImageKeys.full(url))
                .precision(coil3.size.Precision.INEXACT)
                // フル画像の初回表示や再読み込み時の表示切替をなめらかに
                .transitionFactory(CrossfadeTransition.Factory())
                .build()
        } else null
        ZoomableAsyncImage(
            model = model,
            modifier = Modifier.fillMaxSize(),
            minScale = 1f,
            maxScale = 5f,
        )
    }
}

/**
 * ExoPlayer を用いた動画表示。URL から `MediaItem` をセットして再生準備。
 * `DisposableEffect` でライフサイクルに合わせてプレイヤーを解放し、
 * 画面回転時に再生位置を保持する。
 */
@Composable
private fun VideoContent(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var player: ExoPlayer? by remember { mutableStateOf(null) }
    var savedPosition by rememberSaveable { mutableStateOf(0L) }
    var savedPlayWhenReady by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(url) {
        val exo = ExoPlayer.Builder(context).build().also { p ->
            val mediaItem = url?.let { MediaItem.fromUri(Uri.parse(it)) }
            if (mediaItem != null) {
                p.setMediaItem(mediaItem)
                // プレイヤーの準備ができた後に再生位置を復元
                p.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == androidx.media3.common.Player.STATE_READY && savedPosition > 0L) {
                            p.seekTo(savedPosition)
                            p.playWhenReady = savedPlayWhenReady
                            // 一度復元したらリスナーを削除
                            p.removeListener(this)
                        }
                    }
                })
                p.prepare()
            }
        }
        player = exo
        onDispose {
            // 現在の再生位置と再生状態を保存
            savedPosition = exo.currentPosition
            savedPlayWhenReady = exo.playWhenReady
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

/**
 * スクロール可能なテキスト表示。
 */
@Composable
private fun TextContent(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(LocalSpacing.current.l)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        )
        Spacer(modifier = Modifier.padding(LocalSpacing.current.xs))
    }
}

/**
 * デフォルトのテキスト保存ファイル名を生成する。
 * パターン: `<テキスト先頭15文字のサニタイズ>_<yyyyMMdd_HHmmss>`
 *
 * @param text 保存対象テキスト。
 * @return 生成されたファイル名（拡張子なし）。
 */
private fun buildDefaultFileName(text: String?): String {
    val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
    val timestamp = sdf.format(java.util.Date())
    val textHint = (text ?: "text").take(15).replace(Regex("[^a-zA-Z0-9_]"), "_")
    return "${textHint}_$timestamp"
}

/**
 * ピンチ/ドラッグ/ダブルタップに対応したズーム可能な画像コンポーネント。
 * - ピンチで倍率を `minScale..maxScale` にクランプ。
 * - ドラッグでパン（必要に応じてクランプ可能だが本実装では無制限）。
 * - ダブルタップで中間倍率と 1x をトグルし、オフセットをリセット。
 */
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
