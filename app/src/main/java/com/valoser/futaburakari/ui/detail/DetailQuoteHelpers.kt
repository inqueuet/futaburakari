package com.valoser.futaburakari.ui.detail

import android.text.Html
import com.valoser.futaburakari.DetailContent
import java.text.Normalizer

/**
 * Build list of posts that reference the given res number, including the text row and any
 * immediately following media until the next Text/ThreadEndTime.
 */
internal fun buildResReferencesItems(all: List<DetailContent>, resNum: String): List<DetailContent> {
    if (resNum.isBlank()) return emptyList()
    val esc = Regex.escape(resNum)

    fun plainOf(t: DetailContent.Text): String =
        Html.fromHtml(t.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
            .replace("\u200B", "")
            .replace('　', ' ')
            .replace('＞', '>')
            .replace('≫', '>')
            .let { Normalizer.normalize(it, Normalizer.Form.NFKC) }

    val textPatterns = listOf(
        Regex("""\bNo\.?\s*$esc\b""", RegexOption.IGNORE_CASE),
        Regex("""^>+\s*(?:No\.?\s*)?$esc\b""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
        Regex("""\B>+\s*(?:No\.?\s*)?$esc\b""", RegexOption.IGNORE_CASE),
        Regex("""(?<!\d)$esc(?!\d)""")
    )

    val hitIndexes = all.withIndex().filter { (_, c) ->
        c is DetailContent.Text && textPatterns.any { it.containsMatchIn(plainOf(c)) }
    }.map { it.index }

    if (hitIndexes.isEmpty()) return emptyList()

    val groups = mutableListOf<List<DetailContent>>()
    for (i in hitIndexes) {
        val group = mutableListOf<DetailContent>()
        group += all[i]
        var j = i + 1
        while (j < all.size) {
            when (val c = all[j]) {
                is DetailContent.Image, is DetailContent.Video -> { group += c; j++ }
                is DetailContent.Text, is DetailContent.ThreadEndTime -> break
            }
        }
        groups += group
    }

    fun extractResNo(c: DetailContent): Int? = when (c) {
        is DetailContent.Text -> {
            val plain = Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
            Regex("""No\.(\d+)""").find(plain)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        else -> null
    }

    return groups
        .distinctBy { it.firstOrNull()?.id }
        .sortedWith(compareBy<List<DetailContent>> { grp -> extractResNo(grp.firstOrNull() ?: return@compareBy Int.MAX_VALUE) ?: Int.MAX_VALUE })
        .flatten()
}

/**
 * Build list of posts that quote the given source Text's body lines (first-level quote content),
 * including the text row and any immediately following media until the next Text/ThreadEndTime.
 */
internal fun buildBackReferencesByContent(all: List<DetailContent>, source: DetailContent.Text): List<DetailContent> {
    fun normalize(s: String): String = Normalizer.normalize(
        s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
        Normalizer.Form.NFKC
    ).replace(Regex("\\s+"), " ").trim()

    // Extract candidate lines from source body (non-empty, not header-like, length >= 4)
    val srcPlain = Html.fromHtml(source.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
    val candidates: Set<String> = srcPlain.lines()
        .map { normalize(it) }
        .filter { it.isNotBlank() && !it.startsWith("No.", ignoreCase = true) && !it.startsWith("ID:", ignoreCase = true) && it.length >= 4 }
        .toSet()
    if (candidates.isEmpty()) return emptyList()

    // Find posts that contain a quote line exactly equal to any candidate
    val hitIndexes = all.withIndex().filter { (idx, c) ->
        if (c !is DetailContent.Text) return@filter false
        if (c.id == source.id) return@filter false
        val plain = Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
        val quoteLines = Regex("^>+[^\\n]*", RegexOption.MULTILINE)
            .findAll(plain)
            .map { m -> normalize(m.value.replaceFirst(Regex("^>+"), "")) }
            .filter { it.isNotBlank() }
            .toSet()
        quoteLines.any { it in candidates }
    }.map { it.index }

    if (hitIndexes.isEmpty()) return emptyList()

    val groups = mutableListOf<List<DetailContent>>()
    for (i in hitIndexes) {
        val group = mutableListOf<DetailContent>()
        group += all[i]
        var j = i + 1
        while (j < all.size) {
            when (val c = all[j]) {
                is DetailContent.Image, is DetailContent.Video -> { group += c; j++ }
                is DetailContent.Text, is DetailContent.ThreadEndTime -> break
            }
        }
        groups += group
    }

    fun extractResNo(c: DetailContent): Int? = when (c) {
        is DetailContent.Text -> {
            val plain = Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
            Regex("""No\.(\d+)""").find(plain)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        else -> null
    }

    return groups
        .distinctBy { it.firstOrNull()?.id }
        .sortedWith(compareBy<List<DetailContent>> { grp -> extractResNo(grp.firstOrNull() ?: return@compareBy Int.MAX_VALUE) ?: Int.MAX_VALUE })
        .flatten()
}

/**
 * Build list of posts that contain the given free-text query in their plain text,
 * including the text row and any immediately following media until the next Text/ThreadEndTime.
 */
internal fun buildTextSearchItems(all: List<DetailContent>, query: String): List<DetailContent> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    fun plainOf(t: DetailContent.Text): String =
        android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            .replace("\u200B", "")
            .replace('　', ' ')
            .replace('＞', '>')
            .replace('≫', '>')
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC) }

    val hitIndexes = all.withIndex().filter { (_, c) ->
        c is DetailContent.Text && plainOf(c).contains(q, ignoreCase = true)
    }.map { it.index }

    if (hitIndexes.isEmpty()) return emptyList()

    val groups = mutableListOf<List<DetailContent>>()
    for (i in hitIndexes) {
        val group = mutableListOf<DetailContent>()
        group += all[i]
        var j = i + 1
        while (j < all.size) {
            when (val c = all[j]) {
                is DetailContent.Image, is DetailContent.Video -> { group += c; j++ }
                is DetailContent.Text, is DetailContent.ThreadEndTime -> break
            }
        }
        groups += group
    }

    fun extractResNo(c: DetailContent): Int? = when (c) {
        is DetailContent.Text -> {
            val plain = android.text.Html.fromHtml(c.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
            Regex("""No\.(\d+)""").find(plain)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        else -> null
    }

    return groups
        .distinctBy { it.firstOrNull()?.id }
        .sortedWith(compareBy<List<DetailContent>> { grp -> extractResNo(grp.firstOrNull() ?: return@compareBy Int.MAX_VALUE) ?: Int.MAX_VALUE })
        .flatten()
}
