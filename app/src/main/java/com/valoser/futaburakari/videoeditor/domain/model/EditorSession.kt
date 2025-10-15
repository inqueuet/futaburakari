package com.valoser.futaburakari.videoeditor.domain.model

import android.net.Uri

/**
 * 編集セッション
 * 編集中のプロジェクト全体の状態を保持
 */
data class EditorSession(
    val id: String,
    val settings: SessionSettings,
    val videoClips: List<VideoClip>,
    val audioTracks: List<AudioTrack>,
    val markers: List<Marker>,
    val transitions: List<Transition>
) {
    /**
     * セッション全体の長さ（ミリ秒）
     */
    val duration: Long
        get() {
            val videoEnd = videoClips.maxOfOrNull { it.position + it.duration } ?: 0L
            val audioEnd = audioTracks
                .flatMap { it.clips }
                .maxOfOrNull { it.position + it.duration } ?: 0L
            return maxOf(videoEnd, audioEnd)
        }
}

/**
 * セッション設定
 */
data class SessionSettings(
    val fps: Int = 30,
    val resolution: Resolution = Resolution.HD1080,
    val sampleRate: Int = 48000
)

/**
 * 解像度
 */
enum class Resolution(val width: Int, val height: Int) {
    HD720(1280, 720),
    HD1080(1920, 1080)
}

/**
 * 映像クリップ
 */
data class VideoClip(
    val id: String,
    val source: Uri,
    val startTime: Long,              // トリムイン(ms)
    val endTime: Long,                // トリムアウト(ms)
    val position: Long,               // タイムライン位置(ms)
    val speed: Float = 1f,
    val hasAudio: Boolean = true,     // 音声トラックの有無
    val audioEnabled: Boolean = true  // 音声の有効/無効
) {
    /**
     * クリップの長さ（速度を考慮）
     */
    val duration: Long
        get() = ((endTime - startTime) / speed).toLong()

    /**
     * ソース動画の開始時間
     */
    val sourceStartTime: Long
        get() = startTime

    /**
     * ソース動画の終了時間
     */
    val sourceEndTime: Long
        get() = endTime
}

/**
 * 音声トラック
 */
data class AudioTrack(
    val id: String,
    val name: String,
    val clips: List<AudioClip>,
    val volume: Float = 1f,
    val muted: Boolean = false
)

/**
 * 音声クリップ
 */
data class AudioClip(
    val id: String,
    val source: Uri,
    val sourceType: AudioSourceType,
    val startTime: Long,
    val endTime: Long,
    val position: Long,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeIn: FadeDuration? = null,
    val fadeOut: FadeDuration? = null,
    val volumeKeyframes: List<Keyframe> = emptyList()
) {
    val duration: Long
        get() = endTime - startTime
}

/**
 * 音声ソースタイプ
 */
enum class AudioSourceType {
    VIDEO_ORIGINAL,    // 元動画の音声
    MUSIC,             // 音楽ファイル
    RECORDING,         // 録音
    SILENCE           // 無音
}

/**
 * フェードタイプ
 */
enum class FadeType {
    FADE_IN,
    FADE_OUT
}

/**
 * フェード長
 */
enum class FadeDuration(val millis: Long) {
    SHORT(300),
    MEDIUM(500),
    LONG(1000)
}

/**
 * キーフレーム
 */
data class Keyframe(
    val time: Long,
    val value: Float,
    val easing: Easing = Easing.Linear
)

/**
 * イージング
 */
enum class Easing {
    Linear,
    EaseInOut
}

/**
 * マーカー
 */
data class Marker(
    val time: Long,
    val label: String
)

/**
 * トランジション
 */
data class Transition(
    val position: Long,
    val type: TransitionType,
    val duration: Long
)

/**
 * トランジションタイプ
 */
enum class TransitionType {
    CROSSFADE
}

/**
 * 選択状態
 */
sealed class Selection {
    data class VideoClip(val clipId: String) : Selection()
    data class AudioClip(val trackId: String, val clipId: String) : Selection()
    data class Marker(val time: Long) : Selection()
}

/**
 * 編集モード
 */
enum class EditMode {
    NORMAL,           // 通常編集
    AUDIO_TRACK,      // 音声トラック編集
    RANGE_SELECT      // 範囲選択
}

/**
 * サムネイル
 */
data class Thumbnail(
    val time: Long,
    val path: String
)
