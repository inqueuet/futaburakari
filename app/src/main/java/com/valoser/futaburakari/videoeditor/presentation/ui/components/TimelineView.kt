package com.valoser.futaburakari.videoeditor.presentation.ui.components

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.videoeditor.domain.model.EditorSession
import com.valoser.futaburakari.videoeditor.domain.model.Selection
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch

/**
 * タイムラインビュー
 * フィルムストリップ、映像クリップ、音声トラック、波形を表示
 * 中央に固定されたプレイヘッドを持つ
 */
@Composable
fun TimelineView(
    waveformGenerator: com.valoser.futaburakari.videoeditor.media.audio.WaveformGenerator,
    session: EditorSession,
    selection: Selection?,
    playhead: Long,
    isPlaying: Boolean,
    zoom: Float,
    splitMarkerPosition: Long?,
    onClipSelected: (String) -> Unit,
    onClipTrimmed: (String, Long, Long) -> Unit,
    onClipMoved: (String, Long) -> Unit,
    onAudioClipSelected: (String, String) -> Unit,
    onAudioClipTrimmed: (String, String, Long, Long) -> Unit,
    onAudioClipMoved: (String, String, Long) -> Unit,
    onZoomChange: (Float) -> Unit,
    onSeek: (Long) -> Unit, // ★追加
    onMarkerClick: (com.valoser.futaburakari.videoeditor.domain.model.Marker) -> Unit,
    onKeyframeClick: (String, String, com.valoser.futaburakari.videoeditor.domain.model.Keyframe) -> Unit,
    mode: com.valoser.futaburakari.videoeditor.domain.model.EditMode,
    rangeSelection: com.valoser.futaburakari.videoeditor.domain.model.TimeRange?,
    onRangeChange: (Long, Long) -> Unit,
    onTransitionClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val horizontalScrollState = rememberScrollState()
    // ① 自動スクロール中は onSeek を抑制するためのフラグ
    var isAutoScrolling by rememberSaveable { mutableStateOf(false) }
    val timelineDuration = session.duration
    val coroutineScope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        // ※ 固定プレイヘッド用の左右パディングは廃止（実座標プレイヘッドに統一）

        // 連続スクロールをやめ、しきい値を越えた時だけ小さくスクロールする
        val scrollMutex = remember { androidx.compose.foundation.MutatorMutex() }
        // 直近の playhead を記録してループ/巻き戻りを検知（初期値は現在位置）
        var lastPlayhead by rememberSaveable { mutableStateOf(playhead) }
        var isScrolling by remember { mutableStateOf(false) }
        LaunchedEffect(playhead, zoom, timelineDuration, isPlaying) {
            if (!isPlaying) return@LaunchedEffect  // 停止中はスクロール不要
            val contentPx = (timelineDuration * zoom).coerceAtLeast(0f)
            val viewportWidthPx = with(density) { maxWidth.toPx() }
            val maxScroll = (contentPx - viewportWidthPx).coerceAtLeast(0f)
            val playheadXPx = playhead * zoom
            val currentScroll = horizontalScrollState.value.toFloat()

            // 画面内でのプレイヘッド相対位置
            val xInView = playheadXPx - currentScroll
            val leftThreshold  = viewportWidthPx * 0.20f
            val rightThreshold = viewportWidthPx * 0.80f

            // ループ/巻き戻り（例: 1秒以上逆行）を検知したら、このフレームは自動スクロールしない
            val jumpedBack = (lastPlayhead - playhead) > 1000L
            lastPlayhead = playhead
            if (jumpedBack) return@LaunchedEffect


            // しきい値を越えたら、25%位置にくる最小量だけ即時スクロール
            if (xInView < leftThreshold || xInView > rightThreshold) {
                val targetInView = viewportWidthPx * 0.25f
                val desiredScroll = (playheadXPx - targetInView).coerceIn(0f, maxScroll)
                // 自動スクロールは「前方向のみ」適用（後退は無視して揺り戻しを防止）
                if (desiredScroll <= currentScroll) return@LaunchedEffect
                if (isScrolling) return@LaunchedEffect  // 既にスクロール中なら無視
                isScrolling = true
                isAutoScrolling = true
                scrollMutex.mutate {
                    horizontalScrollState.scrollTo(desiredScroll.toInt())
                }
                isAutoScrolling = false
                isScrolling = false
            }
        }

        // ③ 1本指スクロールを含む【すべてのスクロール位置の変化】で再生位置を更新
        LaunchedEffect(zoom) {
            snapshotFlow { horizontalScrollState.value }
                .map { value -> 
                    // ★ zoomが極小値の場合のガード
                    if (zoom > 0.01f) {
                        (value / zoom).toLong()
                    } else {
                        0L
                    }
                }
                .distinctUntilChanged()
                .debounce(30)  // ★ debounce時間を短縮して応答性向上
                .filter { !isAutoScrolling }                // 自動スクロールによるループ回避
                .collectLatest { t ->
                    // ⭐ 閾値を拡大してさらに安定化
                    if (!isPlaying && kotlin.math.abs(t - playhead) > 50L) {
                        onSeek(t)
                    }
                }
        }

        val totalContentWidth = (timelineDuration * zoom).coerceAtLeast(0f)
        val totalContentWidthDp = with(density) { totalContentWidth.toDp() }

        // タイムラインコンテンツ
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(horizontalScrollState)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val newZoom = (zoom * gestureZoom).coerceIn(0.25f, 4f)
                        onZoomChange(newZoom)
                        coroutineScope.launch {
                            // スクロールを先に適用
                            horizontalScrollState.dispatchRawDelta(-pan.x)
                            // 次のスクロール値から赤線下の時刻 = scroll/zoom を計算して通知
                            val nextScroll = horizontalScrollState.value
                            onSeek((nextScroll / newZoom).toLong())
                        }
                    }
                }
        ) {
            // Boxに左右非対称のパディングを追加
            Box(
                modifier = Modifier
                    .width(totalContentWidthDp)
            ) {
                Column {
                    // フィルムストリップ（64dp高さ）
                    FilmStripView(
                        clips = session.videoClips,
                        playhead = playhead,
                        zoom = zoom,
                        timelineDuration = timelineDuration,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 映像クリップトラック（72dp高さ）
                    VideoClipTrack(
                        clips = session.videoClips,
                        selection = selection,
                        playhead = playhead,
                        zoom = zoom,
                        timelineDuration = timelineDuration,
                        onClipSelected = onClipSelected,
                        onClipTrimmed = onClipTrimmed,
                        onClipMoved = onClipMoved,
                        mode = mode,
                        rangeSelection = rangeSelection,
                        onRangeChange = onRangeChange,
                        onTransitionClick = onTransitionClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    )

                    // リンクライン（4dp）
                    Divider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = MaterialTheme.colorScheme.outline
                    )

                    // 音声クリップトラック（各64dp高さ）
                    session.audioTracks.forEach { track ->
                        AudioClipTrack(
                            track = track,
                            selection = selection,
                            playhead = playhead,
                            zoom = zoom,
                            timelineDuration = timelineDuration,
                            onClipSelected = onAudioClipSelected,
                            onClipTrimmed = onAudioClipTrimmed,
                            onClipMoved = onAudioClipMoved,
                            mode = mode,
                            rangeSelection = rangeSelection,
                            onRangeChange = onRangeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        )

                        // キーフレームバー（32dp高さ）
                        KeyframeBar(
                            track = track,
                            playhead = playhead,
                            zoom = zoom,
                            onKeyframeClick = { trackId, clipId, keyframe -> onKeyframeClick(trackId, clipId, keyframe) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                        )

                        // 波形表示（48dp高さ）
                        WaveformView(
                            waveformGenerator = waveformGenerator,
                            track = track,
                            playhead = playhead,
                            zoom = zoom,
                            timelineDuration = timelineDuration,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 時間軸（32dp高さ）
                    TimeRuler(
                        duration = session.duration,
                        playhead = playhead,
                        zoom = zoom,
                        markers = session.markers,
                        splitMarkerPosition = splitMarkerPosition,
                        onMarkerClick = onMarkerClick,
                        onSeekAt = onSeek,   // ★追加
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                    )
                }
            }
        }

        // プレイヘッド（赤線）を実座標で描画：scroll に依存して等速で動く
        val playheadXDp = with(density) {
            (playhead * zoom - horizontalScrollState.value).toDp()
        }
        Divider(
            color = Color.Red,
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .align(Alignment.TopStart)
                .offset(x = playheadXDp)
        )
    }
}
