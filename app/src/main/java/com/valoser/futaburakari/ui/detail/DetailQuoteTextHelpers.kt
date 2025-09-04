package com.valoser.futaburakari.ui.detail

import android.text.Html
import com.valoser.futaburakari.DetailContent
import java.text.Normalizer

// Build items list for a quote token like "> xxx" or ">> No.1234".
// Strategy: extract the core text (without leading '>') and find Text items whose plain
// text contains it (ignore spacing and width variants). Include subsequent media until next Text/End.
internal fun buildQuoteItems(all: List<DetailContent>, token: String): List<DetailContent> {
    val level = token.takeWhile { it == '>' }.length.coerceAtLeast(1)
    val core = token.drop(level).trim()
    if (core.isBlank()) return emptyList()

    fun normalize(s: String): String = Normalizer.normalize(
        s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
        Normalizer.Form.NFKC
    ).replace(Regex("\\s+"), " ").trim()

    val needle = normalize(core)

    // 完全一致: プレーンテキストを行単位で正規化し、1行が needle と等しいもののみヒット
    val textIdx = all.withIndex().filter { (_, c) ->
        if (c !is DetailContent.Text) return@filter false
        val plain = Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
        plain.lines()
            .map { normalize(it) }
            .any { it.isNotBlank() && it == needle }
    }.map { it.index }

    if (textIdx.isEmpty()) return emptyList()

    val result = mutableListOf<DetailContent>()
    for (i in textIdx) {
        result += all[i]
        var j = i + 1
        while (j < all.size) {
            when (val c = all[j]) {
                is DetailContent.Image, is DetailContent.Video -> { result += c; j++ }
                is DetailContent.Text, is DetailContent.ThreadEndTime -> break
            }
        }
    }

    return result.distinctBy { it.id }
}

/**
 * Build items for a quote token and also include back-references that quote the matched source posts.
 * 1) Find source Text items that contain a line exactly equal to the quote core
 * 2) Include each source Text and its following media
 * 3) Include posts that quote any of those source Texts (and their following media)
 */
internal fun buildQuoteAndBackrefItems(all: List<DetailContent>, token: String): List<DetailContent> {
    val level = token.takeWhile { it == '>' }.length.coerceAtLeast(1)
    val core = token.drop(level).trim()
    if (core.isBlank()) return emptyList()

    fun normalize(s: String): String = Normalizer.normalize(
        s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
        Normalizer.Form.NFKC
    ).replace(Regex("\\s+"), " ").trim()

    val needle = normalize(core)

    // 1) find matching source Text indexes (line-level exact match)
    val sourceIdxs = all.withIndex().filter { (_, c) ->
        if (c !is DetailContent.Text) return@filter false
        val lines = Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().lines()
        lines.map { normalize(it) }.any { it.isNotBlank() && it == needle }
    }.map { it.index }
    if (sourceIdxs.isEmpty()) return emptyList()

    // 2) Collect groups for sources
    val groups = mutableListOf<List<DetailContent>>()
    val sourceTexts = mutableListOf<DetailContent.Text>()
    for (i in sourceIdxs) {
        val group = mutableListOf<DetailContent>()
        val t = all[i]
        if (t is DetailContent.Text) sourceTexts += t
        group += t
        var j = i + 1
        while (j < all.size) {
            when (val c = all[j]) {
                is DetailContent.Image, is DetailContent.Video -> { group += c; j++ }
                is DetailContent.Text, is DetailContent.ThreadEndTime -> break
            }
        }
        groups += group
    }

    // 3) For each source, include back-references (quote lines equal to any line of source body)
    for (src in sourceTexts) {
        val back = buildBackReferencesByContent(all, src)
        if (back.isNotEmpty()) {
            // back is already flattened; regroup by first item to keep consistency
            val backGroups = mutableListOf<List<DetailContent>>()
            var k = 0
            while (k < back.size) {
                val first = back[k]
                val group = mutableListOf<DetailContent>()
                group += first
                k++
                while (k < back.size && back[k] !is DetailContent.Text) {
                    group += back[k]
                    k++
                }
                backGroups += group
            }
            groups += backGroups
        }
    }

    // Deduplicate groups by first Text id and flatten; then deduplicate items by id preserving order
    val uniqueGroups = groups.distinctBy { it.firstOrNull()?.id }
    val flat = uniqueGroups.flatten()
    val seen = HashSet<String>()
    val out = ArrayList<DetailContent>(flat.size)
    for (c in flat) {
        if (seen.add(c.id)) out += c
    }
    return out
}
