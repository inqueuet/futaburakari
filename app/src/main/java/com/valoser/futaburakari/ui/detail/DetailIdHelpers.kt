package com.valoser.futaburakari.ui.detail

import android.text.Html
import com.valoser.futaburakari.DetailContent

// Build list: same-ID posts (Text + immediate following media until next Text/End)
internal fun buildIdPostsItems(all: List<DetailContent>, id: String): List<DetailContent> {
    val textIndexes = all.withIndex().filter { (_, c) ->
        c is DetailContent.Text &&
                Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT)
                    .toString()
                    .contains("ID:$id")
    }.map { it.index }
    if (textIndexes.isEmpty()) return emptyList()

    val groups = mutableListOf<List<DetailContent>>()
    for (i in textIndexes) {
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
    val ordered = groups
        .distinctBy { it.firstOrNull()?.id }
        .sortedWith(compareBy<List<DetailContent>> { grp ->
            val head = grp.firstOrNull()
            when (head) {
                null -> Int.MAX_VALUE
                is DetailContent.Text -> {
                    val plain = Html.fromHtml(head.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                    Regex("""No\.(\n|\r|.)*?(\d+)""")
                        .find(plain)?.groupValues?.lastOrNull()?.toIntOrNull() ?: Int.MAX_VALUE
                }
                else -> Int.MAX_VALUE
            }
        })
        .flatten()
    return ordered
}

