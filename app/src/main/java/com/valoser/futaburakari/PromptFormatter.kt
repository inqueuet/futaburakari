package com.valoser.futaburakari

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
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

        // 1) JSON 形式なら JSON 解析ルート
        parseJson(raw)?.let { return it }

        // 2) それ以外はレガシーテキスト解析ルート
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
    // JSON 解析
    // --------------------
    private fun parseJson(raw: String): PromptViewData? {
        val json = findJsonObject(raw) ?: return null

        val positive = mutableListOf<String>()
        val negative = mutableListOf<String>()
        val settings = linkedMapOf<String, String>()

        val prompt = json.optString("prompt", null)
            ?: json.optJSONObject("caption")?.optString("base_caption", null)
            ?: json.optJSONObject("v4_prompt")?.optJSONObject("caption")?.optString("base_caption", null)

        val negativePrompt = json.optString("negative_prompt", null)
            ?: json.optString("uc", null)
            ?: json.optJSONObject("v4_negative_prompt")?.optJSONObject("caption")?.optString("base_caption", null)

        positive += splitTags(prompt)
        negative += splitTags(negativePrompt)

        appendIfExists(settings, "Steps", json, "steps", "num_inference_steps")
        appendIfExists(settings, "Sampler", json, "sampler", "Sampler")
        appendIfExists(settings, "CFG", json, "scale", "guidance_scale")
        appendIfExists(settings, "Seed", json, "seed")

        val w = json.optInt("width", -1)
        val h = json.optInt("height", -1)
        if (w > 0 && h > 0) settings["Size"] = "${w}x$h"
        json.optString("resolution", null)?.takeIf { it.contains("x", true) }
            ?.let { settings.putIfAbsent("Size", it) }

        appendIfExists(settings, "Model", json, "Model", "model")
        appendIfExists(settings, "Model hash", json, "Model hash", "model_hash")

        return PromptViewData(positive, negative, settings)
    }

    private fun findJsonObject(text: String): JSONObject? {
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

    private fun stripSettingsLines(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""
        val headerLike = Regex("""(?im)^[ \t]*[A-Z][\w ]+:\s?.*$\r?\n?""")
        return text.replace(headerLike, "").trim()
    }

    // --------------------
    // タグ分割
    // --------------------
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