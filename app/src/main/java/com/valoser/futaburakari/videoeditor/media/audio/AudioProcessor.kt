package com.valoser.futaburakari.videoeditor.media.audio

import com.valoser.futaburakari.videoeditor.domain.model.AudioClip
import com.valoser.futaburakari.videoeditor.domain.model.FadeDuration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * オーディオ処理クラス
 * フェードイン/アウト、音量調整などの処理を行う
 */
@Singleton
class AudioProcessor @Inject constructor() {

    /**
     * フェードイン処理を適用
     */
    fun applyFadeIn(
        samples: FloatArray,
        sampleRate: Int,
        fadeDuration: FadeDuration
    ): FloatArray {
        val fadeSamples = (sampleRate * fadeDuration.millis / 1000f).toInt()
        val result = samples.copyOf()

        for (i in 0 until min(fadeSamples, samples.size)) {
            val fadeMultiplier = i.toFloat() / fadeSamples
            result[i] = samples[i] * fadeMultiplier
        }

        return result
    }

    /**
     * フェードアウト処理を適用
     */
    fun applyFadeOut(
        samples: FloatArray,
        sampleRate: Int,
        fadeDuration: FadeDuration
    ): FloatArray {
        val fadeSamples = (sampleRate * fadeDuration.millis / 1000f).toInt()
        val result = samples.copyOf()
        val startIndex = (samples.size - fadeSamples).coerceAtLeast(0)

        for (i in startIndex until samples.size) {
            val fadePosition = i - startIndex
            val fadeMultiplier = 1f - (fadePosition.toFloat() / fadeSamples)
            result[i] = samples[i] * fadeMultiplier
        }

        return result
    }

    /**
     * 音量調整を適用
     */
    fun applyVolume(
        samples: FloatArray,
        volume: Float
    ): FloatArray {
        return samples.map { it * volume }.toFloatArray()
    }

    /**
     * キーフレームによる音量調整を適用
     */
    fun applyVolumeKeyframes(
        samples: FloatArray,
        sampleRate: Int,
        clip: AudioClip
    ): FloatArray {
        if (clip.volumeKeyframes.isEmpty()) {
            return samples
        }

        val result = samples.copyOf()
        val keyframes = clip.volumeKeyframes.sortedBy { it.time }

        for (i in samples.indices) {
            val timeMs = (i.toFloat() / sampleRate * 1000).toLong()

            // 現在の時間における音量を計算
            val volume = calculateVolumeAtTime(timeMs, keyframes)
            result[i] = samples[i] * volume
        }

        return result
    }

    /**
     * 指定時間における音量をキーフレームから計算
     */
    private fun calculateVolumeAtTime(
        timeMs: Long,
        keyframes: List<com.valoser.futaburakari.videoeditor.domain.model.Keyframe>
    ): Float {
        if (keyframes.isEmpty()) return 1f

        // 時間の前後のキーフレームを見つける
        val beforeKeyframe = keyframes.lastOrNull { it.time <= timeMs }
        val afterKeyframe = keyframes.firstOrNull { it.time > timeMs }

        return when {
            beforeKeyframe == null -> keyframes.firstOrNull()?.value ?: 1.0f
            afterKeyframe == null -> beforeKeyframe.value
            else -> {
                // 線形補間
                val timeDiff = afterKeyframe.time - beforeKeyframe.time
                val progress = (timeMs - beforeKeyframe.time).toFloat() / timeDiff
                val valueDiff = afterKeyframe.value - beforeKeyframe.value

                beforeKeyframe.value + (valueDiff * progress)
            }
        }
    }

    /**
     * クロスフェードを適用（トランジション用）
     */
    fun applyCrossfade(
        samples1: FloatArray,
        samples2: FloatArray,
        crossfadeDuration: Long,
        sampleRate: Int
    ): FloatArray {
        val crossfadeSamples = (sampleRate * crossfadeDuration / 1000f).toInt()
        val resultSize = samples1.size + samples2.size - crossfadeSamples
        val result = FloatArray(resultSize)

        // 最初のサンプルをコピー（クロスフェード部分を除く）
        val firstPartSize = samples1.size - crossfadeSamples
        System.arraycopy(samples1, 0, result, 0, firstPartSize)

        // クロスフェード部分を処理
        for (i in 0 until crossfadeSamples) {
            val progress = i.toFloat() / crossfadeSamples
            val sample1 = if (firstPartSize + i < samples1.size) samples1[firstPartSize + i] else 0f
            val sample2 = if (i < samples2.size) samples2[i] else 0f

            result[firstPartSize + i] = sample1 * (1f - progress) + sample2 * progress
        }

        // 2番目のサンプルの残りをコピー
        val remainingSize = samples2.size - crossfadeSamples
        if (remainingSize > 0) {
            System.arraycopy(
                samples2, crossfadeSamples,
                result, firstPartSize + crossfadeSamples,
                remainingSize
            )
        }

        return result
    }
}
