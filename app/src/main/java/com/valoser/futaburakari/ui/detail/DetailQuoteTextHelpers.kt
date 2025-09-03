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

    val textIdx = all.withIndex().filter { (_, c) ->
        c is DetailContent.Text && normalize(Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString())
            .contains(needle, ignoreCase = true)
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

