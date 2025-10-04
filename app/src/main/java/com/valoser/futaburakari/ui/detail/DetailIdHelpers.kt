/**
 * Detail 画面向けの ID 絞り込みヘルパー群。
 *
 * 目的:
 * - 指定 ID に紐づく投稿（テキスト＋直後のメディア）をまとめて抽出し、
 *   可能であれば投稿番号で並べ替えたうえでフラット化して返します。
 *
 * 注意:
 * - 検索は HTML をプレーンテキスト化した上で行います。
 * - コメントは処理仕様の備忘録として整備しています。
 */
package com.valoser.futaburakari.ui.detail

import android.text.Html
import com.valoser.futaburakari.DetailContent

/**
 * 指定した ID に紐づく投稿群を抽出して並べ替えるヘルパー。
 *
 * 挙動:
 * - `DetailContent.Text` のプレーンテキストに `"ID:<id>"` を含む行を起点として拾う。
 * - 各起点テキストの直後に連続する `Image` / `Video` を同一グループとして束ね、
 *   次の `Text` または `ThreadEndTime` が現れた時点でそのグループを確定する。
 * - グループの先頭要素（通常は Text）の `id` で重複排除する。
 * - 可能ならテキスト内の投稿番号（例: `No.123456`）をパースして昇順に並べ替える。
 * - 最後にグループをフラット化し、[Text, メディア...] の形で元の出現順を維持したまま返す。
 *
 * 注意:
 * - HTML のプレーンテキスト化は `plainTextOf` デリゲートで行い、デフォルトでは
 *   `Html.fromHtml(..., Html.FROM_HTML_MODE_COMPACT)` を用いる。
 */
// Build list: same-ID posts (Text + immediate following media until next Text/End)
internal fun buildIdPostsItems(
    all: List<DetailContent>,
    id: String,
    plainTextOf: (DetailContent.Text) -> String = { t -> Html.fromHtml(t.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString() },
): List<DetailContent> {
    // 対象IDを含むテキスト要素のインデックスを抽出
    val textIndexes = all.withIndex().filter { (_, c) ->
        c is DetailContent.Text &&
                plainTextOf(c).contains("ID:$id")
    }.map { it.index }
    if (textIndexes.isEmpty()) return emptyList()

    val groups = mutableListOf<List<DetailContent>>()
    for (i in textIndexes) {
        val group = mutableListOf<DetailContent>()
        // 起点テキストを追加し、直後に続くメディアを収集
        group += all[i]
        var j = i + 1
        while (j < all.size) {
            when (val c = all[j]) {
                is DetailContent.Image, is DetailContent.Video -> { group += c; j++ }
                // 次のテキスト or スレ終端が来たら1グループ終了
                is DetailContent.Text, is DetailContent.ThreadEndTime -> break
            }
        }
        groups += group
    }
    // 先頭要素の id で重複排除し、投稿番号（No.xxx）が取れる場合はその数値で昇順ソート
    val ordered = groups
        .distinctBy { it.firstOrNull()?.id }
        .sortedWith(compareBy<List<DetailContent>> { grp ->
            val head = grp.firstOrNull()
            when (head) {
                null -> Int.MAX_VALUE
                is DetailContent.Text -> {
                    val plain = plainTextOf(head)
                    Regex("""No\.(\n|\r|.)*?(\d+)""")
                        .find(plain)?.groupValues?.lastOrNull()?.toIntOrNull() ?: Int.MAX_VALUE
                }
                else -> Int.MAX_VALUE
            }
        })
        .flatten()
    return ordered
}
