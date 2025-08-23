package com.example.hutaburakari

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.Html
import org.json.JSONObject
import org.json.JSONException

object PromptFormatter {

    data class PromptViewData(
        val positive: List<String>,
        val negative: List<String>,
        val settings: Map<String, String>
    )

    /** 生テキスト(JSON/プレーン両対応) → 表示用データ */
    fun parse(raw: String?): PromptViewData? {
        if (raw.isNullOrBlank()) return null

        // 1) JSONパス（Animagine系/拡張JSON 等）
        parseJson(raw)?.let { return it }

        // 2) テキストパス（"Negative prompt:" 区切り + 末尾メタ）
        return parseLegacyText(raw)
    }

    /** 表示用データ → HTML(CharSequence) */
    fun toHtml(pd: PromptViewData): CharSequence {
        val sb = StringBuilder()

        fun escape(s: String) = Html.escapeHtml(s)

        if (pd.positive.isNotEmpty()) {
            sb.append("<h4>Positive</h4>")
            sb.append("<div>")
            pd.positive.forEach { tag ->
                sb.append("・").append(escape(tag)).append("<br>")
            }
            sb.append("</div>")
        }
        if (pd.negative.isNotEmpty()) {
            sb.append("<h4>Negative</h4>")
            sb.append("<div>")
            pd.negative.forEach { tag ->
                sb.append("・").append(escape(tag)).append("<br>")
            }
            sb.append("</div>")
        }
        if (pd.settings.isNotEmpty()) {
            sb.append("<h4>Settings</h4>")
            sb.append("<table>")
            pd.settings.forEach { (k, v) ->
                sb.append("<tr><td><b>")
                    .append(escape(k))
                    .append("</b></td><td>")
                    .append(escape(v))
                    .append("</td></tr>")
            }
            sb.append("</table>")
        }

        return Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_COMPACT)
    }

    // --------------------
    // JSON 解析
    // --------------------
    private fun parseJson(raw: String): PromptViewData? {
        val json = findJsonObject(raw) ?: return null

        val positive = mutableListOf<String>()
        val negative = mutableListOf<String>()
        val settings = linkedMapOf<String, String>()

        // 代表的キー
        val prompt = json.optString("prompt", null)
            ?: json.optJSONObject("caption")?.optString("base_caption", null)
            ?: json.optJSONObject("v4_prompt")?.optJSONObject("caption")?.optString("base_caption", null)

        val negativePrompt = json.optString("negative_prompt", null)
            ?: json.optString("uc", null)
            ?: json.optJSONObject("v4_negative_prompt")?.optJSONObject("caption")?.optString("base_caption", null)

        // 文字列→タグ配列
        positive += splitTags(prompt)
        negative += splitTags(negativePrompt)

        // よくあるパラメータ類
        appendIfExists(settings, "Steps", json, "steps", "num_inference_steps")
        appendIfExists(settings, "Sampler", json, "sampler", "Sampler")
        appendIfExists(settings, "CFG", json, "scale", "guidance_scale")
        appendIfExists(settings, "Seed", json, "seed")

        val w = json.optInt("width", -1)
        val h = json.optInt("height", -1)
        if (w > 0 && h > 0) settings["Size"] = "${w}x$h"
        json.optString("resolution", null)?.takeIf { it.contains("x", true) }?.let { settings.putIfAbsent("Size", it) }

        appendIfExists(settings, "Model", json, "Model", "model")
        appendIfExists(settings, "Model hash", json, "Model hash", "model_hash")

        return PromptViewData(positive, negative, settings)
    }

    private fun findJsonObject(text: String): JSONObject? {
        // テキスト内の最初の {...} を試す
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val cand = text.substring(start, end + 1)
            return try { JSONObject(cand) } catch (_: JSONException) { null }
        }
        return null
    }

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
    private fun parseLegacyText(raw: String): PromptViewData {
        val settings = linkedMapOf<String, String>()

        // 1) 「Negative prompt:」の“見出し行”を正規表現で見つける
        //    見出しの直後から、次の見出し（大文字始まりの Key: など）や文末までを Negative 本文として抜く
        val negHeaderRegex =
            Regex("""(?im)^[ \t]*Negative\s*prompt\s*:?[ \t]*\r?\n?""") // 見出しそのもの
        val metaHeaderRegex =
            Regex("""(?im)^[ \t]*[A-Z][\w ]+:\s?.*$""") // Steps:, Sampler: などの見出し行

        val negHeaderMatch = negHeaderRegex.find(raw)
        val positiveBlob: String
        val negativeBlob: String?

        if (negHeaderMatch != null) {
            // Positive は見出し開始位置より前だけを採用（←ここが重要）
            val positiveEnd = negHeaderMatch.range.first
            positiveBlob = raw.substring(0, positiveEnd)

            // Negative 本文は見出し直後から、次のメタ見出し or 末尾まで
            val rest = raw.substring(negHeaderMatch.range.last + 1)
            val nextMeta = metaHeaderRegex.find(rest)
            negativeBlob = if (nextMeta != null) {
                rest.substring(0, nextMeta.range.first)
            } else {
                rest
            }
        } else {
            // Negative 見出しがなければ全文を Positive 候補に
            positiveBlob = raw
            negativeBlob = null
        }

        // 2) 設定行（Steps: など）を Positive / Negative から除去しておく
        val cleanedPositive = stripSettingsLines(positiveBlob)
        val cleanedNegative = stripSettingsLines(negativeBlob)

        // 3) タグ分割（(tag:1.3) → "tag (×1.3)" 正規化）
        val positive = splitTags(cleanedPositive)
        val negative = splitTags(cleanedNegative)

        // 4) 末尾の設定（メタ）を抽出
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

    /** Positive/Negative 本文から Steps: などの“見出し行”を取り除く */
    private fun stripSettingsLines(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""
        // 見出しっぽい行（"Key: value"）を削除
        val headerLike = Regex("""(?im)^[ \t]*[A-Z][\w ]+:\s?.*$\r?\n?""")
        return text.replace(headerLike, "").trim()
    }

    // "a, b, (c:1.3)" など → ["a", "b", "c (×1.3)"]
    private fun splitTags(src: String?): List<String> {
        if (src.isNullOrBlank()) return emptyList()
        return src.split(',', '、', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeWeight(it) }
    }

    private fun normalizeWeight(tag: String): String {
        // (word:1.2) / word:1.2 → "word (×1.2)"
        val rx1 = Regex("""^\((.+?):\s*([0-9.]+)\)$""")
        val rx2 = Regex("""^(.+?):\s*([0-9.]+)$""")
        rx1.matchEntire(tag)?.let {
            val (w, s) = it.destructured
            return "${w.trim()} (×${s.trim()})"
        }
        rx2.matchEntire(tag)?.let {
            val (w, s) = it.destructured
            return "${w.trim()} (×${s.trim()})"
        }
        return tag
    }
}