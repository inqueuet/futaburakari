package com.valoser.futaburakari.ui.detail

import android.text.Html
import com.valoser.futaburakari.DetailContent
import java.text.Normalizer

/**
 * 引用/参照の集計ヘルパ関数群。
 *
 * 提供機能:
 * - No.参照（`buildResReferencesItems`）
 * - 本文内容を引用した被引用の探索（`buildBackReferencesByContent`）
 * - 引用元（自身）＋被引用の結合（`buildSelfAndBackrefItems`）
 * - フリーテキスト検索（`buildTextSearchItems`）
 * - ファイル名参照（`buildFilenameReferencesItems`）
 */
/**
 * No.（投稿番号）参照の一覧を構築します。
 * - 本文プレーンテキストを正規化（ZWSP除去、全角空白/＞/≫→半角、NFKC）し、以下のいずれかに該当する Text をヒットとみなします:
 *   - 大文字小文字無視で「No. <num>」を含む
 *   - 行頭または本文中の引用（'>'）に「No. <num>」が含まれる
 *   - 数字の前後が他の数字でない素の <num>
 * - ヒットごとに Text 行と直後の Image/Video を次の Text/ThreadEndTime までまとめます。
 * - 先頭要素の id で重複排除し、可能なら抽出した No. 昇順でグループを並べ替えてからフラット化します。
 * - 引用されたNo.の場合、元のNo.とそれを引用しているNo.の両方を表示します。
 */
internal fun buildResReferencesItems(
    all: List<DetailContent>,
    resNum: String,
    plainTextOf: (DetailContent.Text) -> String = { t -> android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString() },
): List<DetailContent> {
    if (resNum.isBlank()) return emptyList()
    val esc = Regex.escape(resNum)

    fun plainOf(t: DetailContent.Text): String =
        plainTextOf(t)
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
            val plain = plainTextOf(c)
            Regex("""No\.(\d+)""").find(plain)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        else -> null
    }

    // 元のNo.の投稿も含めるため、該当する投稿を検索
    val originalPost = all.find { content ->
        content is DetailContent.Text && content.resNum == resNum
    }

    val allResults = mutableListOf<List<DetailContent>>()

    // 元の投稿を最初に追加（存在する場合）
    if (originalPost != null) {
        val originalIndex = all.indexOf(originalPost)
        if (originalIndex >= 0) {
            val originalGroup = mutableListOf<DetailContent>()
            originalGroup += all[originalIndex]
            var j = originalIndex + 1
            while (j < all.size) {
                when (val c = all[j]) {
                    is DetailContent.Image, is DetailContent.Video -> { originalGroup += c; j++ }
                    is DetailContent.Text, is DetailContent.ThreadEndTime -> break
                }
            }
            allResults += originalGroup
        }
    }

    // 引用している投稿を追加
    allResults += groups

    return allResults
        .distinctBy { it.firstOrNull()?.id }
        .sortedWith(compareBy<List<DetailContent>> { grp -> extractResNo(grp.firstOrNull() ?: return@compareBy Int.MAX_VALUE) ?: Int.MAX_VALUE })
        .flatten()
}

/**
 * 指定した Text の本文を引用している投稿（被引用）を集計します。
 * - ソース本文（正規化後）の行から候補文を抽出（「No.」「ID:」などのヘッダ風は除外、長さ>=2）。`extraCandidates` があれば同様に正規化の上で追加。
 * - 任意の引用行（先頭の '>' は段数無視で剥がす）が候補文に完全一致する投稿をヒットとする。
 *   `extraCandidates` が空でない場合は、非引用行の完全一致も許容。
 * - 正規化は ZWSP 除去、全角空白/＞/≫→半角、NFKC、連続空白の圧縮、trim を行う。
 * - ヒットごとに Text 行と直後の Image/Video を次の Text/ThreadEndTime までまとめ、No. 昇順で整列のうえフラット化。
 */
internal fun buildBackReferencesByContent(
    all: List<DetailContent>,
    source: DetailContent.Text,
    extraCandidates: Set<String> = emptySet(),
    plainTextOf: (DetailContent.Text) -> String = { t -> android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString() },
): List<DetailContent> {
    fun normalize(s: String): String = Normalizer.normalize(
        s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
        Normalizer.Form.NFKC
    ).replace(Regex("\\s+"), " ").trim()

    // ソース本文から候補行を抽出（空でない/ヘッダ風でない/長さ>=2）
    val srcPlain = plainTextOf(source)
    val candidates: Set<String> = srcPlain.lines()
        .map { normalize(it) }
        .filter { it.isNotBlank() && !it.startsWith("No.", ignoreCase = true) && !it.startsWith("ID:", ignoreCase = true) && it.length >= 2 }
        .toSet()
        .let {
            if (extraCandidates.isEmpty()) it
            else it + extraCandidates.map { s -> normalize(s) }
        }
    if (candidates.isEmpty()) return emptyList()

    // 候補と完全一致する引用行を含む投稿を抽出
    val hitIndexes = all.withIndex().filter { (idx, c) ->
        if (c !is DetailContent.Text) return@filter false
        if (c.id == source.id) return@filter false
        val plain = plainTextOf(c)
        val quoteLines = plain.lines()
            .filter { it.trim().startsWith(">") }
            .map { normalize(it.trim().replaceFirst(Regex("^>+"), "")) }
            .filter { it.isNotBlank() }
            .toSet()
        if (quoteLines.any { it in candidates }) return@filter true

        // extraCandidates がある場合は、プレーン本文行での完全一致も許容
        if (extraCandidates.isNotEmpty()) {
            val plainLines = plain.lines().map { normalize(it) }.filter { it.isNotBlank() }
            if (plainLines.any { it in candidates }) {
                return@filter true
            }
        }

        false
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
            val plain = plainTextOf(c)
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
 * ソース本文（自身の Text + 直下メディア）と、その被引用の一覧を結合して返します。
 * - まずソース自身のグループを作成し、続いて `buildBackReferencesByContent` の結果グループを連結。
 * - グループ先頭 id で重複排除し、アイテム単位でも id で一意化しつつフラット化します。
 */
internal fun buildSelfAndBackrefItems(
    all: List<DetailContent>,
    source: DetailContent.Text,
    extraCandidates: Set<String> = emptySet(),
    plainTextOf: (DetailContent.Text) -> String = { t -> android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString() },
): List<DetailContent> {
    // Build group for the source itself
    val srcIndex = all.indexOfFirst { it.id == source.id }
    if (srcIndex < 0) return emptyList()
    val groups = mutableListOf<List<DetailContent>>()
    run {
        val g = mutableListOf<DetailContent>()
        g += all[srcIndex]
        var j = srcIndex + 1
        while (j < all.size) {
            when (val c = all[j]) {
                is DetailContent.Image, is DetailContent.Video -> { g += c; j++ }
                is DetailContent.Text, is DetailContent.ThreadEndTime -> break
            }
        }
        groups += g
    }
    // 被引用のグループを後ろに連結
    val back = buildBackReferencesByContent(all, source, extraCandidates = extraCandidates, plainTextOf = plainTextOf)
    if (back.isNotEmpty()) {
        var k = 0
        while (k < back.size) {
            val first = back[k]
            val g = mutableListOf<DetailContent>()
            g += first
            k++
            while (k < back.size && back[k] !is DetailContent.Text) {
                g += back[k]
                k++
            }
            groups += g
        }
    }
    // 先頭 id でグループ重複を除去し、要素単位でも id で一意化しつつフラット化
    val uniqueGroups = groups.distinctBy { it.firstOrNull()?.id }
    val flat = uniqueGroups.flatten()
    val seen = HashSet<String>()
    val out = ArrayList<DetailContent>(flat.size)
    for (c in flat) if (seen.add(c.id)) out += c
    return out
}

/**
 * フリーテキスト（大文字小文字無視）で本文を検索して一致した投稿を返します。
 * - 本文は正規化（ZWSP 除去、全角空白/＞/≫→半角、NFKC）。
 * - ヒット単位で Text 行＋直後のメディアをまとめ、No. 昇順で整列後にフラット化します。
 */
internal fun buildTextSearchItems(
    all: List<DetailContent>,
    query: String,
    plainTextOf: (DetailContent.Text) -> String = { t -> android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString() },
): List<DetailContent> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    fun plainOf(t: DetailContent.Text): String =
        plainTextOf(t)
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
            val plain = plainTextOf(c)
            Regex("""(?i)(?:No|Ｎｏ)[\.\uFF0E]?\s*(\d+)""")
                .find(plain)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        else -> null
    }

    return groups
        .distinctBy { it.firstOrNull()?.id }
        .sortedWith(compareBy<List<DetailContent>> { grp -> extractResNo(grp.firstOrNull() ?: return@compareBy Int.MAX_VALUE) ?: Int.MAX_VALUE })
        .flatten()
}

/**
 * ファイル名に関連する投稿を集計します。
 *
 * 集計対象:
 * - ソース投稿: 指定ファイル名（大文字小文字無視、URL末尾セグメントに正規化）と一致する Image/Video を直後に持つ Text。
 * - 参照投稿: 本文にファイル名（末尾セグメント基準・大文字小文字無視）を含む、または引用行のコアを正規化した結果がファイル名に一致/部分一致する Text。
 *
 * いずれも Text 行と直後の Image/Video を次の Text/ThreadEndTime までまとめ、
 * 先頭 id で重複排除してから、可能なら No. 昇順に並べ替えてフラット化します。
 * URL のクエリ/フラグメントは無視し、末尾セグメントで比較します。
 */
internal fun buildFilenameReferencesItems(
    all: List<DetailContent>,
    fileName: String,
    plainTextOf: (DetailContent.Text) -> String = { t -> android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString() },
): List<DetailContent> {
    val needle = fileName.trim()
    if (needle.isEmpty()) return emptyList()

    fun normalize(s: String): String = java.text.Normalizer.normalize(
        s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
        java.text.Normalizer.Form.NFKC
    ).trim()

    fun plainOf(t: DetailContent.Text): String = plainTextOf(t)

    fun parentTextIndex(from: Int): Int {
        var i = from
        while (i >= 0) {
            if (all[i] is DetailContent.Text) return i
            i--
        }
        return -1
    }

    fun lastSegmentNoQuery(s: String?): String? = s?.substringAfterLast('/')?.substringBefore('?')?.substringBefore('#')
    val normalizedNeedle = lastSegmentNoQuery(needle) ?: needle
    val lower = normalizedNeedle.lowercase()

    // 1) Source groups: media items whose filename or URL suffix matches
    val sourceTextIdx = all.withIndex().mapNotNull { (i, c) ->
        when (c) {
            is DetailContent.Image -> {
                val fn = lastSegmentNoQuery(c.fileName)
                val urlSeg = lastSegmentNoQuery(c.imageUrl)
                val hit = (fn != null && fn.equals(normalizedNeedle, ignoreCase = true)) || (urlSeg != null && urlSeg.equals(normalizedNeedle, ignoreCase = true))
                if (hit) parentTextIndex(i).takeIf { it >= 0 } else null
            }
            is DetailContent.Video -> {
                val fn = lastSegmentNoQuery(c.fileName)
                val urlSeg = lastSegmentNoQuery(c.videoUrl)
                val hit = (fn != null && fn.equals(normalizedNeedle, ignoreCase = true)) || (urlSeg != null && urlSeg.equals(normalizedNeedle, ignoreCase = true))
                if (hit) parentTextIndex(i).takeIf { it >= 0 } else null
            }
            else -> null
        }
    }.toMutableSet()

    // 2) Reference posts: text that mentions the filename (in body or as quote-line)
    val refTextIdx = all.withIndex().mapNotNull { (i, c) ->
        if (c !is DetailContent.Text) return@mapNotNull null
        val plain = plainOf(c)
        val hasInBody = plain.contains(normalizedNeedle, ignoreCase = true)
        val hasInQuote = plain.lines().any { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith(">")) return@any false
            val core = trimmed.replaceFirst(Regex("^>+"), "").trim()
            val norm = normalize(core)
            norm.equals(normalizedNeedle, ignoreCase = true) || norm.contains(lower, ignoreCase = true)
        }
        if (hasInBody || hasInQuote) i else null
    }.toSet()

    val allTextIdx = (sourceTextIdx + refTextIdx).toMutableSet()
    if (allTextIdx.isEmpty()) return emptyList()

    // Build groups from each text index
    val groups = mutableListOf<List<DetailContent>>()
    for (i in allTextIdx) {
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
            val plain = plainTextOf(c)
            Regex("""(?i)(?:No|Ｎｏ)[\.\uFF0E]?\s*(\d+)""")
                .find(plain)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        else -> null
    }

    return groups
        .distinctBy { it.firstOrNull()?.id }
        .sortedWith(compareBy<List<DetailContent>> { grp -> extractResNo(grp.firstOrNull() ?: return@compareBy Int.MAX_VALUE) ?: Int.MAX_VALUE })
        .flatten()
}
