package com.valoser.futaburakari

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * プロンプト文字列（JSON/プレーンテキスト/ComfyUI 形式）を解析し、
 * 画面表示向けのデータおよび `Spannable` を生成するユーティリティ。
 */
object PromptFormatter {

    /**
     * 画面表示用に分解されたプロンプトデータ。
     *
     * - `positive` / `negative`: タグごとのリスト（必要に応じて重み表記を正規化）。
     * - `settings`: 生成時の設定値（Steps, Sampler, CFG, Seed, Model, Size など）。
     */
    data class PromptViewData(
        val positive: List<String>,
        val negative: List<String>,
        val settings: Map<String, String>
    )

    /** 生テキストを ComfyUI JSON → 一般 JSON → レガシーテキストの順に解析して表示用データへ変換する。 */
    fun parse(raw: String?): PromptViewData? {
        if (raw.isNullOrBlank()) return null

        // 1) ComfyUI JSON 形式の解析を試みる
        parseComfyUiJson(raw)?.let { return it }

        // 2) 一般的なJSON 形式なら JSON 解析ルート
        parseJson(raw)?.let { return it }

        // 3) それ以外はレガシーテキスト解析ルート
        return parseLegacyText(raw)
    }

    /** 表示用データ → Spannable (見出し太字・改行区切り) */
    fun toSpannable(pd: PromptViewData): CharSequence {
        val sb = SpannableStringBuilder()

        fun addHeader(text: String) {
            val start = sb.length
            sb.append(text).append("\n")
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                start + text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        fun addLines(lines: List<String>) {
            lines.forEach { line -> sb.append(line).append("\n") }
        }

        fun addKv(label: String, value: String) {
            val start = sb.length
            sb.append(label).append(": ")
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                start + label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append(value).append("\n")
        }

        if (pd.positive.isNotEmpty()) {
            addHeader("Positive")
            addLines(pd.positive)
            sb.append("\n")
        }
        if (pd.negative.isNotEmpty()) {
            addHeader("Negative")
            addLines(pd.negative)
            sb.append("\n")
        }
        if (pd.settings.isNotEmpty()) {
            addHeader("Settings")
            pd.settings.forEach { (k, v) -> addKv(k, v) }
        }
        return sb
    }

    // --------------------
    // ComfyUI JSON 解析
    // --------------------
    /**
     * ComfyUI のワークフロー JSON からプロンプトと設定を抽出します。
     *
     * - KSampler ノードを起点に Positive/Negative のテキスト入力を辿ります。
     * - モデル名、スケジューラ、seed、ステップ数、サイズなどを `settings` に格納します。
     * - ComfyUI 形式でない場合は `null` を返します。
     */
    private fun parseComfyUiJson(raw: String): PromptViewData? {
        try {
            val json = JSONObject(raw)

            // KSamplerノードを探す (設定値の多くが含まれるため)
            val samplerNodeId = json.keys().asSequence().find {
                json.optJSONObject(it)?.optString("class_type")?.startsWith("KSampler") == true
            } ?: return null // KSamplerが見つからなければComfyUI形式ではないと判断

            val samplerNode = json.getJSONObject(samplerNodeId)
            val samplerInputs = samplerNode.getJSONObject("inputs")

            // Positive/NegativeプロンプトのノードIDを取得
            val positiveNodeId = samplerInputs.optConnectionNodeId("positive")
            val negativeNodeId = samplerInputs.optConnectionNodeId("negative")

            if (positiveNodeId == null || negativeNodeId == null) return null

            // プロンプトテキストを抽出
            val positiveText = json.optJSONObject(positiveNodeId)
                ?.optJSONObject("inputs")
                ?.optStringOrNull("text")
            val negativeText = json.optJSONObject(negativeNodeId)
                ?.optJSONObject("inputs")
                ?.optStringOrNull("text")

            val positive = splitTags(positiveText)
            val negative = splitTags(negativeText)
            val settings = linkedMapOf<String, String>()

            // 設定値を抽出
            samplerInputs.optStringOrNull("seed")?.let { settings["Seed"] = it }
            samplerInputs.optStringOrNull("steps")?.let { settings["Steps"] = it }
            samplerInputs.optStringOrNull("cfg")?.let { settings["CFG"] = it }
            samplerInputs.optStringOrNull("sampler_name")?.let { settings["Sampler"] = it }
            samplerInputs.optStringOrNull("scheduler")?.let { settings["Scheduler"] = it }
            samplerInputs.optStringOrNull("denoise")?.let { settings["Denoise"] = it }


            // モデル名を探す
            val modelNodeId = samplerInputs.optConnectionNodeId("model")
            if (modelNodeId != null) {
                val modelNode = json.optJSONObject(modelNodeId)
                // LoraTagLoader -> CheckpointLoaderSimple のように辿る
                val checkpointNodeId = modelNode?.optJSONObject("inputs")?.optConnectionNodeId("model")
                if (checkpointNodeId != null) {
                    json.optJSONObject(checkpointNodeId)
                        ?.optJSONObject("inputs")
                        ?.optStringOrNull("ckpt_name")
                        ?.let { settings["Model"] = it }
                }
            }


            // 画像サイズを探す (EmptyLatentImage)
            val latentNodeId = samplerInputs.optConnectionNodeId("latent_image")
            if (latentNodeId != null) {
                val latentInputs = json.optJSONObject(latentNodeId)?.optJSONObject("inputs")
                if (latentInputs != null) {
                    val w = latentInputs.optInt("width", -1)
                    val h = latentInputs.optInt("height", -1)
                    if (w > 0 && h > 0) settings["Size"] = "${w}x${h}"
                }
            }

            return PromptViewData(positive, negative, settings)

        } catch (_: JSONException) {
            return null
        }
    }

    private fun JSONObject.optConnectionNodeId(key: String): String? {
        val value = opt(key) ?: return null
        return when (value) {
            is JSONArray -> value.firstConnectionNodeId()
            is String -> value.takeUnless { it.isBlank() }
            is Number -> value.toString()
            else -> null
        }
    }

    private fun JSONArray.firstConnectionNodeId(): String? {
        if (length() == 0) return null
        val first = opt(0)
        return when (first) {
            is JSONArray -> first.firstConnectionNodeId()
            is JSONObject -> first.optString("id").takeUnless { it.isBlank() }
            is String -> first.takeUnless { it.isBlank() }
            is Number -> first.toString()
            else -> null
        }
    }


    // --------------------
    // JSON 解析 (元々のコード)
    // --------------------
    /**
     * 文字列中に含まれる JSON オブジェクトを検出して解析します。
     *
     * - 既知のキー（`prompt` / `negative_prompt` など）から各値を抽出します。
     * - `settings` には存在するもののみを追加します。
     */
    private fun parseJson(raw: String): PromptViewData? {
        val json = findJsonObject(raw) ?: return null

        val positive = mutableListOf<String>()
        val negative = mutableListOf<String>()
        val settings = linkedMapOf<String, String>()

        val prompt = json.optStringOrNull("prompt")
            ?: json.optJSONObject("caption")?.optStringOrNull("base_caption")
            ?: json.optJSONObject("v4_prompt")?.optJSONObject("caption")?.optStringOrNull("base_caption")

        val negativePrompt = json.optStringOrNull("negative_prompt")
            ?: json.optStringOrNull("uc")
            ?: json.optJSONObject("v4_negative_prompt")?.optJSONObject("caption")?.optStringOrNull("base_caption")

        positive += splitTags(prompt)
        negative += splitTags(negativePrompt)

        appendIfExists(settings, "Steps", json, "steps", "num_inference_steps")
        appendIfExists(settings, "Sampler", json, "sampler", "Sampler")
        appendIfExists(settings, "CFG", json, "scale", "guidance_scale")
        appendIfExists(settings, "Seed", json, "seed")
        appendIfExists(settings, "Software", json, "software")

        val w = json.optInt("width", -1)
        val h = json.optInt("height", -1)
        if (w > 0 && h > 0) settings["Size"] = "${w}x$h"
        json.optStringOrNull("resolution")?.takeIf { it.contains("x", true) }
            ?.let { settings.putIfAbsent("Size", it) }

        appendIfExists(settings, "Model", json, "Model", "model")
        appendIfExists(settings, "Model hash", json, "Model hash", "model_hash")

        // Tensor.Art 対応: modelid からURLを生成
        val modelId = json.optStringOrNull("modelid") ?: json.optStringOrNull("model_id")
        if (!modelId.isNullOrBlank()) {
            settings["Model ID"] = modelId
            settings.putIfAbsent("Model URL", "https://tensor.art/models/$modelId")
        }

        return PromptViewData(positive, negative, settings)
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val raw = opt(key)
        return when (raw) {
            null, JSONObject.NULL -> null
            else -> raw.toString()
        }
    }

    /**
     * 文字列内で最初に現れる `{` から最後の `}` までを抜き出して
     * `JSONObject` 化を試みます。失敗時は `null`。
     */
    private fun findJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val cand = text.substring(start, end + 1)
            return try { JSONObject(cand) } catch (_: JSONException) { null }
        }
        return null
    }

    /**
     * 指定のラベルで、最初に見つかったキーの値を `settings` に追加します。
     * 値が空や null の場合は無視されます。
     */
    private fun appendIfExists(dst: MutableMap<String, String>, label: String, json: JSONObject, vararg keys: String) {
        for (k in keys) {
            val v = json.opt(k)
            if (v != null && v.toString().isNotBlank()) {
                dst[label] = v.toString()
                return
            }
        }
    }

    // --------------------
    // レガシーテキスト解析
    // --------------------
    /**
     * プレーンテキスト形式のプロンプトを解析します。
     *
     * - `Negative prompt` 見出しを境にポジ/ネガを分割。
     * - 大文字で始まるヘッダ風の設定行を除去してからタグを分割。
     * - 正規表現で Steps/Sampler/CFG/Seed/Size/Model/Model hash などを抽出します。
     */
    private fun parseLegacyText(raw: String): PromptViewData {
        val settings = linkedMapOf<String, String>()

        // Negative prompt 見出しを探す
        val negHeaderRegex = Regex("""(?im)^[ \t]*Negative\s*prompt\s*:?[ \t]*\r?\n?""")
        val metaHeaderRegex = Regex("""(?im)^[ \t]*[A-Z][\w ]+:\s?.*$""")

        val negHeaderMatch = negHeaderRegex.find(raw)
        val positiveBlob: String
        val negativeBlob: String?

        if (negHeaderMatch != null) {
            val positiveEnd = negHeaderMatch.range.first
            positiveBlob = raw.substring(0, positiveEnd)

            val rest = raw.substring(negHeaderMatch.range.last + 1)
            val nextMeta = metaHeaderRegex.find(rest)
            negativeBlob = if (nextMeta != null) {
                rest.substring(0, nextMeta.range.first)
            } else {
                rest
            }
        } else {
            positiveBlob = raw
            negativeBlob = null
        }

        val cleanedPositive = stripSettingsLines(positiveBlob)
        val cleanedNegative = stripSettingsLines(negativeBlob)

        val positive = splitTags(cleanedPositive)
        val negative = splitTags(cleanedNegative)

        fun pick(pattern: String, label: String) {
            Regex(pattern, RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.let {
                settings[label] = it.trim()
            }
        }
        pick("""Steps:\s*([0-9]+)""", "Steps")
        pick("""Sampler:\s*([^\n,]+)""", "Sampler")
        pick("""(?:CFG\s*scale|CFG):\s*([0-9.]+)""", "CFG")
        pick("""Seed:\s*([0-9]+)""", "Seed")
        pick("""Size:\s*([0-9]+\s*x\s*[0-9]+)""", "Size")
        pick("""(?:Model\s*hash|Model hash):\s*([A-Fa-f0-9]+)""", "Model hash")
        pick("""(?:Model):\s*([^\n,]+)""", "Model")

        return PromptViewData(positive, negative, settings)
    }

    /**
     * 大文字で始まる `Xxx: value` 形式の行を削除します。
     */
    private fun stripSettingsLines(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""
        val headerLike = Regex("""(?im)^[ \t]*[A-Z][\w ]+:\s?.*$\r?\n?""")
        return text.replace(headerLike, "").trim()
    }

    // --------------------
    // タグ分割
    // --------------------
    /**
     * タグ文字列をカンマで分割します。
     *
     * - 括弧 `()` や山括弧 `<>` の内部、エスケープされたカンマは分割しません。
     * - 重み表記は `normalizeWeight` でユーザー向け表記に整えます。
     */
    private fun splitTags(src: String?): List<String> {
        if (src.isNullOrBlank()) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depthParen = 0
        var depthAngle = 0
        var escape = false

        fun flush() {
            val raw = sb.toString().trim()
            if (raw.isNotEmpty()) out += normalizeWeight(raw)
            sb.setLength(0)
        }

        src.forEach { ch ->
            if (escape) { sb.append(ch); escape = false; return@forEach }
            when (ch) {
                '\\' -> { sb.append(ch); escape = true }
                '('  -> { depthParen++; sb.append(ch) }
                ')'  -> { depthParen = (depthParen - 1).coerceAtLeast(0); sb.append(ch) }
                '<'  -> { depthAngle++; sb.append(ch) }
                '>'  -> { depthAngle = (depthAngle - 1).coerceAtLeast(0); sb.append(ch) }
                ','  -> if (depthParen == 0 && depthAngle == 0) flush() else sb.append(ch)
                else -> sb.append(ch)
            }
        }
        flush()

        return out.map { it.replace("\\(", "(").replace("\\)", ")").replace("\\,", ",") }
    }

    /**
     * 重み表記をユーザー向けに正規化します。
     *
     * - `(tag: 1.2)` または `tag: 1.2` を `tag (×1.2)` に変換。
     * - `<>` で囲まれた特殊タグ（例: LoRA 表記）はそのまま返します。
     */
    private fun normalizeWeight(tag: String): String {
        val t = tag.trim()
        if (t.startsWith("<") && t.endsWith(">")) return t

        val paren = Regex("""^\(([^():]+):\s*([0-9.]+)\)$""")
        paren.matchEntire(t)?.let { m ->
            return "${m.groupValues[1].trim()} (×${m.groupValues[2].trim()})"
        }

        val plain = Regex("""^([^():]+):\s*([0-9.]+)$""")
        plain.matchEntire(t)?.let { m ->
            return "${m.groupValues[1].trim()} (×${m.groupValues[2].trim()})"
        }

        return t
    }
}
