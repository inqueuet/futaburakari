package com.valoser.futaburakari

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * TTS（Text-To-Speech）音声読み上げを管理するクラス。
 *
 * 機能:
 * - スレ本文の自動読み上げ（順次再生）
 * - 再生/一時停止/停止制御
 * - 読み上げ速度調整
 * - 現在読み上げ中のレス番号追跡
 */
class TtsManager(context: Context) {

    private companion object {
        private val whitespaceRegex = Regex("\\s+")
        private val deletedSummaryPattern = Regex("""削除された(?:レス|記事)が\\d+件あります(?:[。.．･・·]?見る)?""")
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _currentResNum = MutableStateFlow<String?>(null)
    val currentResNum: StateFlow<String?> = _currentResNum.asStateFlow()

    private var textQueue = mutableListOf<TtsItem>()
    private var currentIndex = 0

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.apply {
                    language = Locale.JAPANESE
                    setSpeechRate(1.0f)
                    setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            utteranceId?.let { id ->
                                val item = textQueue.find { it.id == id }
                                _currentResNum.value = item?.resNum
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            // 次のアイテムを再生
                            playNext()
                        }

                        override fun onError(utteranceId: String?) {
                            _state.value = TtsState.Error("読み上げエラーが発生しました")
                        }
                    })
                }
                isInitialized = true
            } else {
                _state.value = TtsState.Error("TTS初期化に失敗しました")
            }
        }
    }

    /**
     * 複数の本文を順次読み上げる
     * NGや削除されたレスは改めて除外してからキューに積まれる
     */
    fun playTexts(items: List<DetailContent.Text>, plainTextOf: (DetailContent.Text) -> String) {
        if (!isInitialized) {
            _state.value = TtsState.Error("TTSが初期化されていません")
            return
        }

        stop()

        textQueue.clear()
        currentIndex = 0

        items.forEach { item ->
            val plain = plainTextOf(item)
            // 「削除されました」「NGワード」などの非表示メッセージを含むレスを除外
            if (isDeletedOrNgPost(plain)) {
                return@forEach
            }

            val bodyOnly = extractBodyText(plain)
            if (bodyOnly.isNotBlank()) {
                textQueue.add(TtsItem(
                    id = item.id,
                    text = bodyOnly,
                    resNum = item.resNum
                ))
            }
        }

        if (textQueue.isEmpty()) {
            _state.value = TtsState.Error("読み上げる内容がありません")
            return
        }

        _state.value = TtsState.Playing
        playNext()
    }

    /**
     * 特定のレス番号から再生を開始
     */
    fun playFromResNum(resNum: String, items: List<DetailContent.Text>, plainTextOf: (DetailContent.Text) -> String) {
        playTexts(items, plainTextOf)

        val index = textQueue.indexOfFirst { it.resNum == resNum }
        if (index >= 0) {
            currentIndex = index
            tts?.stop()
            playNext()
        }
    }

    private fun playNext() {
        if (currentIndex >= textQueue.size) {
            _state.value = TtsState.Completed
            _currentResNum.value = null
            return
        }

        val item = textQueue[currentIndex]
        currentIndex++

        tts?.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, item.id)
    }

    /**
     * 一時停止
     */
    fun pause() {
        if (_state.value is TtsState.Playing) {
            tts?.stop()
            _state.value = TtsState.Paused
        }
    }

    /**
     * 再開（停止したレスの次から再生される）
     */
    fun resume() {
        if (_state.value is TtsState.Paused) {
            _state.value = TtsState.Playing
            playNext()
        }
    }

    /**
     * 停止
     */
    fun stop() {
        tts?.stop()
        _state.value = TtsState.Idle
        _currentResNum.value = null
        textQueue.clear()
        currentIndex = 0
    }

    /**
     * 次のレスへスキップ
     */
    fun skipNext() {
        if (_state.value is TtsState.Playing) {
            tts?.stop()
            playNext()
        }
    }

    /**
     * 前のレスへ戻る
     */
    fun skipPrevious() {
        if (_state.value is TtsState.Playing && currentIndex > 1) {
            currentIndex -= 2
            tts?.stop()
            playNext()
        }
    }

    /**
     * 読み上げ速度を設定（0.5 ～ 2.0）
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * リソース解放
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /**
     * 削除されたレスやNGワードで非表示になっているレスかを判定
     */
    private fun isDeletedOrNgPost(plain: String): Boolean {
        val trimmed = plain.trim()
        val normalized = whitespaceRegex.replace(trimmed, "")
        if (deletedSummaryPattern.containsMatchIn(normalized)) {
            return true
        }
        // 「削除されました」「NGワード」などの典型的なメッセージパターン
        return trimmed.contains("削除されました") ||
                trimmed.contains("スレッドを立てた人によって削除されました") ||
                trimmed.contains("書き込みをした人によって削除されました") ||
                trimmed.contains("NGワード") ||
                trimmed.contains("NG設定") ||
                trimmed.contains("非表示")
    }

    /**
     * 本文からヘッダーや引用を除いた読み上げ用テキストを抽出
     */
    private fun extractBodyText(plain: String): String {
        fun normalize(s: String): String = java.text.Normalizer.normalize(
            s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
            java.text.Normalizer.Form.NFKC
        )

        val idPat = Regex("""(?i)\bID(?:[:：]|無し)\b[\w./+\-]*""")
        val noPat = Regex("""(?i)\b(?:No|Ｎｏ)[\.\uFF0E]?\s*\d+\b""")
        val dateTimePat = Regex("""(?:(?:\d{2}|\d{4})/\d{1,2}/\d{1,2}).*?\d{1,2}:\d{2}:\d{2}""")
        val fileInfoHeadPat = Regex("""(?i)^\s*(?:ファイル名|画像|ファイル)[:：].*""")
        val ext = "(?:jpg|jpeg|png|gif|webp|bmp|mp4|webm|avi|mov|mkv)"
        val fileInfoGenericPat = Regex("""(?i)^\s*.*?\.$ext\s*[\-ー－]?\s*\([^)]*\).*""")
        val fileSizePat = Regex("""\[\d+\s*[KMGT]?B\]""")

        val lines = plain.lines()
        var start = 0

        // ヘッダー行をスキップ
        while (start < lines.size) {
            val raw = lines[start]
            val trimmed = raw.trimStart()
            if (trimmed.isBlank()) { start++; continue }
            val norm = normalize(trimmed)
            val isHeader = idPat.containsMatchIn(norm) || noPat.containsMatchIn(norm) ||
                    dateTimePat.containsMatchIn(norm) || fileInfoHeadPat.containsMatchIn(norm) ||
                    fileInfoGenericPat.containsMatchIn(norm)
            val isLeadQuote = trimmed.startsWith(">")
            if (isHeader || isLeadQuote) { start++; continue }
            break
        }

        val bodyLines = lines.drop(start).dropLastWhile { it.isBlank() }

        // URLや記号を読みやすく整形
        return bodyLines.joinToString("\n") { line ->
            var cleaned = line
            // URLを除去
            cleaned = cleaned.replace(Regex("""https?://[^\s]+"""), "")
            // 連続する記号を削除
            cleaned = cleaned.replace(Regex("""[＞>]{2,}"""), "")
            // ファイルサイズ表記を除去（例: [237656 B], [1.2 MB]）
            cleaned = cleaned.replace(fileSizePat, "")
            cleaned.trim()
        }.trim()
    }

    data class TtsItem(
        val id: String,
        val text: String,
        val resNum: String?
    )

    sealed class TtsState {
        object Idle : TtsState()
        object Playing : TtsState()
        object Paused : TtsState()
        object Completed : TtsState()
        data class Error(val message: String) : TtsState()
    }
}
