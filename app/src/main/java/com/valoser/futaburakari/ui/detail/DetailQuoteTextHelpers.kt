package com.valoser.futaburakari.ui.detail

import android.text.Html
import com.valoser.futaburakari.DetailContent
import java.text.Normalizer

/**
 * 引用トークン（"> xxx" や ">> No.1234" のような形式）に対応するアイテムを構築します。
 *
 * 方針:
 * - トークンを正規化し、先頭の '>' の段数分（最低1文字）を取り除いてコア文字列を抽出。
 * - 本文（正規化後）を行単位で比較し、コア文字列と完全一致する行を含む Text をヒットとする。
 * - 各ヒット Text の直後に続く Image/Video を、次の Text/ThreadEndTime までまとめて取得。
 * - 収集したアイテムは id 単位で重複を除いて返します。
 */
internal fun buildQuoteItems(
    all: List<DetailContent>,
    token: String,
    plainTextOf: (DetailContent.Text) -> String = { t -> Html.fromHtml(t.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString() },
): List<DetailContent> {
    // トークンを正規化（先頭の全角空白を半角へ、全角 ＞ 系を '>' へ、前方空白を除去）
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
        val plain = plainTextOf(c)
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
 * 引用トークンに対応するアイテムを構築し、さらにその引用元を引用している被引用も含めます。
 *
 * 手順:
 * 1) コア文字列と行単位で完全一致する Text を「引用元」として抽出。
 *    `threadTitle` と一致する場合はスレOP（最初の Text）も引用元に加えます。
 * 2) 各引用元 Text と、その直後に続くメディアをまとめて追加。
 * 3) 各引用元の本文を引用している投稿（被引用）を本文引用と >>No の両面から集計し、直後メディアも含めて追加。
 *    OP が引用元かつスレタイ一致のときはスレタイ本文一致も候補に含めます。
 * 4) グループ先頭と各要素の id で重複を除去した一覧を返します。
 */
internal fun buildQuoteAndBackrefItems(
    all: List<DetailContent>,
    token: String,
    threadTitle: String?,
    plainTextOf: (DetailContent.Text) -> String = { t -> Html.fromHtml(t.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString() },
): List<DetailContent> {
    // トークンを正規化（先頭の全角空白を半角へ、全角 ＞ 系を '>' へ、前方空白を除去）
    val t0 = token.replace('\u3000', ' ').replace('＞', '>').replace('≫', '>').trimStart()
    val level = t0.takeWhile { it == '>' }.length.coerceAtLeast(1)
    val core = t0.drop(level).trim()
    if (core.isBlank()) return emptyList()

    fun normalize(s: String): String = Normalizer.normalize(
        s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
        Normalizer.Form.NFKC
    ).replace(Regex("\\s+"), " ").trim()

    val needle = normalize(core)

    // 1) 引用元 Text のインデックスを抽出（行単位の完全一致）
    val sourceIdxsMutable = all.withIndex().filter { (_, c) ->
        if (c !is DetailContent.Text) return@filter false
        val lines = plainTextOf(c).lines()
        lines.map { normalize(it) }.any { it.isNotBlank() && it == needle }
    }.map { it.index }
    val sourceIdxs = sourceIdxsMutable.toMutableSet()
    // スレタイと一致する場合は OP（最初の Text）も引用元扱いにする
    val titleNorm = threadTitle?.let { normalize(it) }
    val firstTextIdx = all.indexOfFirst { it is DetailContent.Text }
    val titleMatched = !titleNorm.isNullOrBlank() && titleNorm == needle && firstTextIdx >= 0
    if (titleMatched) sourceIdxs.add(firstTextIdx)
    if (sourceIdxs.isEmpty()) return emptyList()

    // 2) 引用元のグループを収集（Text + 直後のメディア）
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

    // 3) 各引用元に対し、本文を引用している被引用（およびその直後メディア）を追加
    for (src in sourceTexts) {
        val isOp = all.indexOf(src) == firstTextIdx
        val extra = if (titleMatched && isOp) setOf(needle) else emptySet()
        // 本文内容に基づく被引用
        val back = buildBackReferencesByContent(all, src, extraCandidates = extra, plainTextOf = plainTextOf)
        if (back.isNotEmpty()) {
            // back はフラット化済みなので、先頭要素単位で再グルーピングして整形
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
        // No. に基づく被引用（>>No）
        run {
            val plain = plainTextOf(src)
            val rn = Regex("""(?i)(?:No|Ｎｏ)[\.\uFF0E]?\s*(\d+)""")
                .find(plain)?.groupValues?.getOrNull(1)
            if (!rn.isNullOrBlank()) {
                val byNum = buildResReferencesItems(all, rn, plainTextOf = plainTextOf)
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

    // 先頭 Text の id でグループを重複排除しフラット化。さらに要素も id で一意化（順序維持）。
    val uniqueGroups = groups.distinctBy { it.firstOrNull()?.id }
    val flat = uniqueGroups.flatten()
    val seen = HashSet<String>()
    val out = ArrayList<DetailContent>(flat.size)
    for (c in flat) {
        if (seen.add(c.id)) out += c
    }
    return out
}
