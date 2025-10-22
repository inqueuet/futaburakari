package com.valoser.futaburakari.videoeditor.media.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import com.valoser.futaburakari.videoeditor.domain.model.AudioClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 波形生成クラス
 * 大型波形表示用の高密度サンプリング
 */
@Singleton
class WaveformGenerator @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {
    companion object {
        // 大型波形表示用の高密度サンプリング
        private const val SAMPLES_PER_SECOND = 20 // 0.05秒間隔（より詳細に）
    }

    /**
     * 波形データを生成
     */
    suspend fun generate(
        clip: AudioClip
    ): Result<FloatArray> = withContext(Dispatchers.IO) {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, clip.source, null)

            // オーディオトラックを選択
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex == -1) {
                extractor.release()
                return@withContext Result.success(FloatArray(0))
            }

            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val samples = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                var isDecoding = true
                var samplesInCurrentWindow = mutableListOf<Float>()
                val samplesPerWindow = sampleRate / SAMPLES_PER_SECOND

                while (isDecoding) {
                    // 入力バッファにデータをキュー
                    val inputBufferId = decoder.dequeueInputBuffer(10000)
                    if (inputBufferId >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferId)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferId, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isDecoding = false
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(
                                inputBufferId, 0, sampleSize,
                                presentationTimeUs, 0
                            )
                            extractor.advance()
                        }
                    }

                    // 出力バッファからデコード済みデータを取得
                    val outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferId >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferId)!!

                        // PCMデータを処理
                        for (i in 0 until bufferInfo.size / 2) {
                            val sample = outputBuffer.getShort(i * 2).toFloat() / 32768f
                            samplesInCurrentWindow.add(sample)

                            // ウィンドウが満杯になったらRMS計算
                            if (samplesInCurrentWindow.size >= samplesPerWindow) {
                                val rms = calculateRMS(samplesInCurrentWindow)
                                samples.add(rms)
                                samplesInCurrentWindow.clear()
                            }
                        }

                        decoder.releaseOutputBuffer(outputBufferId, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            isDecoding = false
                        }
                    }
                }

                // 残りのサンプルを処理
                if (samplesInCurrentWindow.isNotEmpty()) {
                    val rms = calculateRMS(samplesInCurrentWindow)
                    samples.add(rms)
                }

            } finally {
                decoder.stop()
                decoder.release()
                extractor.release()
            }

            Result.success(samples.toFloatArray())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * RMS（二乗平均平方根）を計算
     */
    private fun calculateRMS(samples: List<Float>): Float {
        if (samples.isEmpty()) return 0f
        val sum = samples.fold(0f) { acc, sample -> acc + sample * sample }
        return sqrt(sum / samples.size)
    }

    /**
     * オーディオトラックを選択
     */
    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
}
