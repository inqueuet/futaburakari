package com.valoser.futaburakari.ui.detail

import android.text.Html
import com.valoser.futaburakari.DetailContent
import java.text.Normalizer

// Build items list for a quote token like "> xxx" or ">> No.1234".
// Strategy: extract the core text (without leading '>') and find Text items that have
// a line exactly equal to it (after normalization). Include subsequent media until next Text/End.
internal fun buildQuoteItems(all: List<DetailContent>, token: String): List<DetailContent> {
    // Normalize token: strip leading spaces, convert full-width variants to ASCII
    val t0 = token.replace('\u3000', ' ').replace('＞', '>').replace('≫', '>').trimStart()
    val level = t0.takeWhile { it == '>' }.length.coerceAtLeast(1)
    val core = t0.drop(level).trim()
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
internal fun buildQuoteAndBackrefItems(all: List<DetailContent>, token: String, threadTitle: String?): List<DetailContent> {
    // Normalize token: strip leading spaces, convert full-width variants to ASCII
    val t0 = token.replace('\u3000', ' ').replace('＞', '>').replace('≫', '>').trimStart()
    val level = t0.takeWhile { it == '>' }.length.coerceAtLeast(1)
    val core = t0.drop(level).trim()
    if (core.isBlank()) return emptyList()

    fun normalize(s: String): String = Normalizer.normalize(
        s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
        Normalizer.Form.NFKC
    ).replace(Regex("\\s+"), " ").trim()

    val needle = normalize(core)

    // 1) find matching source Text indexes (line-level exact match)
    val sourceIdxsMutable = all.withIndex().filter { (_, c) ->
        if (c !is DetailContent.Text) return@filter false
        val lines = Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().lines()
        lines.map { normalize(it) }.any { it.isNotBlank() && it == needle }
    }.map { it.index }
    val sourceIdxs = sourceIdxsMutable.toMutableSet()
    // If the quote matches the thread title, treat OP (first Text) as a source as well
    val titleNorm = threadTitle?.let { normalize(it) }
    val firstTextIdx = all.indexOfFirst { it is DetailContent.Text }
    val titleMatched = !titleNorm.isNullOrBlank() && titleNorm == needle && firstTextIdx >= 0
    if (titleMatched) sourceIdxs.add(firstTextIdx)
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
        val isOp = all.indexOf(src) == firstTextIdx
        val extra = if (titleMatched && isOp) setOf(needle) else emptySet()
        // content-based backrefs
        val back = buildBackReferencesByContent(all, src, extraCandidates = extra)
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
        // number-based backrefs (>>No)
        run {
            val plain = Html.fromHtml(src.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
            val rn = Regex("""(?i)(?:No|Ｎｏ)[\.\uFF0E]?\s*(\d+)""")
                .find(plain)?.groupValues?.getOrNull(1)
            if (!rn.isNullOrBlank()) {
                val byNum = buildResReferencesItems(all, rn)
                if (byNum.isNotEmpty()) {
                    var k = 0
                    while (k < byNum.size) {
                        val first = byNum[k]
                        val g = mutableListOf<DetailContent>()
                        g += first
                        k++
                        while (k < byNum.size && byNum[k] !is DetailContent.Text) {
                            g += byNum[k]
                            k++
                        }
                        groups += g
                    }
                }
            }
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
