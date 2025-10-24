/**
 * スレ詳細コンテンツを表示するリストビュー（Compose版）。
 * - 本ファイルではリスト表示や検索ナビ、メディアのプリフェッチ等を扱います。
 * - 実装仕様をコメントとして補足し、Compose 版リストの振る舞いを明文化しています。
 */
package com.valoser.futaburakari.ui.detail

/**
 * スレ詳細のコンテンツを表示する Compose 版リスト。
 *
 * 機能概要:
 * - 表示要素は `DetailContent` の列（Text / Image / Video / ThreadEndTime）。
 * - ブロック構造: 1つの Text に続く Image/Video を同一ブロックとして扱い、ブロック末尾のみ区切り線を描画。
 * - 検索: `searchQuery` にマッチする要素のインデックスを算出し、`onProvideSearchNavigator` で Prev/Next 関数を渡す。
 * - アノテーション/クリック: 本文(Text)内の `No.xxxx`、引用行(> または 全角＞)、`ID:xxxx`、URL、ファイル名（xxx.jpg 等）、そうだね(+/＋/そうだね/そうだねxN) を検出してクリック可能にする。
 *   - 未タグ領域の「短押し」は被引用一覧（このレスを引用している投稿）を表示。
 *   - 本文の「長押し」は本文のみを引用して返信（`onBodyClick`）。
 *   - タイトル行が `threadTitle` に一致する場合は引用としても扱う（全角/半角/空白の差は正規化して比較）。
 * - URL タップは外部ブラウザ起動。ファイル名タップは「該当メディアの投稿＋ファイル名を言及している投稿」の一覧シートを表示。
 * - メニュー: 以下のメニューを表示（ボタンは中央寄せ・キャンセルなし）。
 *   - No: 返信(>No.xxx) / 確認（引用している内容表示）
 *   - ファイル名: 返信(>ファイル名) / 確認（引用している内容表示）
 *   - 本文: 返信(>本文のみ) / 確認（被引用一覧） / NG
 *   - ID: 同一IDの投稿 / NGに追加（ID メニューは DetailScreen 側で生成）
 * - 「そうだね」表示は親から渡されるカウントで楽観的に上書き表示する（`applySodaneDisplay`）。
 *   - 引用行を除き、行内の No 有無に関わらずクリック可能（行内に No が無ければ自投稿の No をフォールバック）。
 * - スクロール状態の保存/復元、最大既読序数の通知(`onVisibleMaxOrdinal`)に対応。
 * - 画像/動画の直下に表示するプロンプト文は選択コピー可能（SelectionContainer）。
 * - 画像/動画のタップでメディアビューへ遷移（拡大/動画再生、コピー/保存機能はメディア側で提供）。
 * - ファイル名の「追記表示」は廃止。本文中のファイル名検出・クリックのみをサポート（ファイル名参照の集計シートを開く）。
 * - プロンプト文は HTML→プレーン化して表示（リンク検出や装飾は行わない）。
 * - No の検出は表記ゆれに対応（ドットの有無/全角、No と番号の間の空白/改行）。
 *   また、日付や閉じカッコ `)` の直後に No が隣接してしまう場合、および `ID:` と `No` が隣接する場合は
 *   表示テキスト側で空白を補い、可読性とクリック検出（そうだね/No.リンク）の安定性を高める。
 *   表示整形は NFKC 正規化（全角→半角など）を行い、非空白直後に `No` が来る一般ケースにも空白を補って取りこぼしを防ぐ。
 * - パフォーマンス: 可視範囲の変化に応じて前方/後方のメディア（画像/動画）を Coil にプリフェッチし、
 *   スクロール時の初回表示を高速化（前方3件・後方1件を目安に先読み）。
 * - 本文返信の引用テキスト: 先頭ヘッダ（ID/ID無し/No/日付時刻/ファイル情報/先行引用）を除いた「本文のみ」を `>` で引用。
 * - レイアウト: `modifier` で外側からサイズ指定を受け取る。
 *   - 画面全体のリストでは `Modifier.fillMaxSize()` を渡す。
 *   - ボトムシート内では `Modifier.wrapContentHeight()` を渡し、シート側で `heightIn(max=...)` を併用して
 *     内容が少ないときは内容高さ、内容が多いときは上限までに抑える。
 */
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.ui.theme.LocalSpacing
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
 
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
 
import com.valoser.futaburakari.DetailContent
import com.valoser.futaburakari.R
import android.text.Html
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.request.transitionFactory
import coil3.transition.CrossfadeTransition
import com.valoser.futaburakari.image.ImageKeys
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import android.util.Patterns
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.collection.LruCache
import java.text.Normalizer

/**
 * Compose の LazyColumn で利用する安定キー。
 * DetailContent の各要素は ViewModel/Worker 層で衝突しない ID を付与しているため、
 * 原則としてそのまま利用する。空文字の場合のみ型＋indexでフォールバック。
 */
private fun stableKey(item: DetailContent, index: Int): String {
    val id = item.id
    if (id.isNotBlank()) return id
    return when (item) {
        is DetailContent.Text -> "text_fallback_$index"
        is DetailContent.Image -> "image_fallback_$index"
        is DetailContent.Video -> "video_fallback_$index"
        is DetailContent.ThreadEndTime -> "thread_end_fallback_$index"
    }
}

/**
 * スレ詳細のコンテンツを表示する Compose リスト。
 *
 * 概要:
 * - `DetailContent` の列を描画（本文/画像/動画/終了時刻）。ブロック区切りや検索ハイライト、クリックアクションに対応。
 * - No./引用/ID/URL/ファイル名/そうだね の検出とメニュー表示、被引用一覧や同一IDなどの遷移はコールバックで委譲。
 * - スクロール位置の保存/復元、最大既読序数の通知、検索 Prev/Next ナビの提供に対応。
 *
 * パラメータ（主なもの）:
 * - `items`: 表示対象アイテムのリスト。
 * - `searchQuery`: 検索語。ハイライトとナビゲータの起点に使用。
 * - `onQuoteClick`/`onResNumClick`/`onResNumConfirmClick`/`onResNumDelClick`/`onBodyClick`/`onAddNgFromBody`/`onIdClick`: 各クリックのハンドラ。
 * - `onProvideSearchNavigator`: Prev/Next の関数を上位へ提供するためのコールバック。
 * - `sodaneCounts`/`onSetSodaneCount`/`getSodaneState`: 「そうだね」の表示上書きと重複防止に関する情報。
 * - `listState`/`initialScrollIndex`/`initialScrollOffset`/`onSaveScroll`: スクロール状態の外部管理。
 * - `onVisibleMaxOrdinal`: 画面内で50%以上見えている本文の最大序数を通知。
 */

private val imageRequestCache = LruCache<String, ImageRequest>(2000)
private val headersCache = LruCache<String, NetworkHeaders>(100)

private data class ScrollSnapshot(val index: Int, val offset: Int, val anchorId: String?)
private data class RestoreSignature(val anchorId: String, val key: Triple<Int, Int, Int>)

private fun createImageRequest(
    context: android.content.Context,
    url: String,
    referer: String?,
    forDisplay: Boolean = true
): ImageRequest {
    val cacheKey = "$url|$referer|$forDisplay"
    return imageRequestCache.get(cacheKey) ?: run {
        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(ImageKeys.full(url))
            .diskCacheKey(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .precision(Precision.INEXACT)
            .transitionFactory(CrossfadeTransition.Factory())
            .apply {
                if (!referer.isNullOrBlank()) {
                    httpHeaders(createHeaders(referer))
                }
                // サイズ指定を削除してキャッシュ効率を優先
                // Coilが自動的に適切なサイズでスケーリングを行う
            }
            .build()
        imageRequestCache.put(cacheKey, request)
        request
    }
}

private fun createHeaders(referer: String): NetworkHeaders {
    return headersCache.get(referer) ?: run {
        val headers = NetworkHeaders.Builder()
            .add("Referer", referer)
            .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .build()
        headersCache.put(referer, headers)
        headers
    }
}

/**
 * DetailContent.Text が削除されたレスかどうかを判定する。
 * HTML内に削除メッセージが含まれている場合、削除レスと見なす。
 * - 「スレッドを立てた人によって削除されました」
 * - 「書き込みをした人によって削除されました」
 */
private fun isDeletedRes(text: DetailContent.Text): Boolean {
    return text.htmlContent.contains("スレッドを立てた人によって削除されました") ||
           text.htmlContent.contains("書き込みをした人によって削除されました")
}

/** 空白圧縮用の正規表現（重複レス検出向け）。 */
private val duplicateWhitespaceRegex = Regex("\\s+")

/**
 * テキスト本文から重複検出に用いるキーを生成する。
 * - 行ごとにZWSPや全角空白/＞/≫を正規化し、引用行(>)とヘッダ行(No./ID:)を除外。
 * - 残った本文行を NFKC 正規化＋空白圧縮したうえで改行結合し、完全一致判定キーとする。
 * - 本文行が存在しない場合は null を返し、重複判定の対象外とする。
 */
private fun buildDuplicateContentKey(plainText: String): String? {
    val bodyLines = mutableListOf<String>()
    for (line in plainText.lines()) {
        var normalized = line.replace("\u200B", "")
            .replace('　', ' ')
            .replace('＞', '>')
            .replace('≫', '>')
            .trim()
        if (normalized.isEmpty()) continue
        if (normalized.startsWith(">")) continue
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC)
        val collapsed = duplicateWhitespaceRegex.replace(normalized, " ").trim()
        if (collapsed.isEmpty()) continue
        if (collapsed.startsWith("No.", ignoreCase = true)) continue
        if (collapsed.startsWith("ID:", ignoreCase = true)) continue
        bodyLines += collapsed
    }

    if (bodyLines.isEmpty()) return null
    return bodyLines.joinToString("\n")
}

/**
 * 荒らし判定向けに本文行を正規化する。
 * - ZWSP や全角空白/＞を標準化し、NFKC 正規化＋空白圧縮を行う。
 * - 空文字列やヘッダ行(No./ID:)は対象外とする。
 */
private fun normalizeBodyLineForQuoteDetection(raw: String): String? {
    var normalized = raw
        .replace("\u200B", "")
        .replace('　', ' ')
        .replace('＞', '>')
        .trim()
    if (normalized.isEmpty()) return null
    normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC)
    normalized = duplicateWhitespaceRegex.replace(normalized, " ").trim()
    if (normalized.isEmpty()) return null
    if (normalized.startsWith("No.", ignoreCase = true)) return null
    if (normalized.startsWith("ID:", ignoreCase = true)) return null
    return normalized
}

/**
 * 実在しない本文への引用行（>xxx）が突然出現したレスを除去する。
 *
 * 直前までのレス本文（引用行を除く）を記録し、シングル引用行 (> もしくは 全角＞) が
 * 既出本文に一致しない場合は荒らしと見なしてレス＋後続メディアを非表示とする。
 */
private fun filterPhantomQuoteResponses(
    items: List<DetailContent>,
    plainTextCache: Map<String, String>,
    plainTextOf: (DetailContent.Text) -> String
): List<DetailContent> {
    if (items.isEmpty()) return items
    val seenBodyLines = mutableSetOf<String>()
    val result = mutableListOf<DetailContent>()
    var index = 0
    var anyFiltered = false

    while (index < items.size) {
        val item = items[index]
        if (item is DetailContent.Text) {
            val plain = plainTextCache[item.id] ?: plainTextOf(item)
            val lines = plain.lines()

            var hide = false
            lines@ for (line in lines) {
                val normalizedSource = line
                    .replace("\u200B", "")
                    .replace('　', ' ')
                    .replace('＞', '>')
                val trimmedStart = normalizedSource.trimStart()
                if (!trimmedStart.startsWith(">")) continue
                val leadingGtCount = trimmedStart.takeWhile { it == '>' }.length
                if (leadingGtCount != 1) continue
                val quoteBody = trimmedStart.drop(leadingGtCount).trimStart()
                val normalizedQuote = normalizeBodyLineForQuoteDetection(quoteBody) ?: continue
                if (normalizedQuote.length < 2) continue
                if (normalizedQuote.all { it.isDigit() }) continue
                if (normalizedQuote !in seenBodyLines) {
                    hide = true
                    break@lines
                }
            }

            if (hide) {
                anyFiltered = true
                index++
                while (index < items.size) {
                    val attachment = items[index]
                    if (attachment is DetailContent.Image || attachment is DetailContent.Video) {
                        anyFiltered = true
                        index++
                    } else {
                        break
                    }
                }
                continue
            } else {
                result += item
                for (line in lines) {
                    val normalized = normalizeBodyLineForQuoteDetection(line) ?: continue
                    val trimmedStart = line
                        .replace("\u200B", "")
                        .replace('　', ' ')
                        .replace('＞', '>')
                        .trimStart()
                    val leadingGt = trimmedStart.takeWhile { it == '>' }.length
                    if (leadingGt == 0) {
                        seenBodyLines += normalized
                    }
                }
                index++
            }
        } else {
            result += item
            index++
        }
    }

    return if (anyFiltered) result else items
}

/**
 * 同一本文レスの過剰表示を制限する。対象レスを除去した際は、直後の Image/Video も併せて非表示にする。
 *
 * @param items 対象コンテンツ一覧（削除レス除去後）
 * @param threshold 同一本文を許容する回数（この回数までは表示、それ以降は非表示）
 */
private fun filterDuplicateResponses(
    items: List<DetailContent>,
    threshold: Int,
    plainTextCache: Map<String, String>,
    plainTextOf: (DetailContent.Text) -> String
): List<DetailContent> {
    if (items.isEmpty()) return items
    val limit = threshold.coerceAtLeast(1)
    val result = mutableListOf<DetailContent>()
    val counters = mutableMapOf<String, Int>()
    var index = 0
    var anyFiltered = false

    while (index < items.size) {
        val item = items[index]
        if (item is DetailContent.Text) {
            val plain = plainTextCache[item.id] ?: plainTextOf(item)
            val key = buildDuplicateContentKey(plain)
            if (key != null) {
                val newCount = (counters[key] ?: 0) + 1
                counters[key] = newCount
                if (newCount <= limit) {
                    result += item
                    index++
                } else {
                    anyFiltered = true
                    index++
                    while (index < items.size) {
                        val attachment = items[index]
                        if (attachment is DetailContent.Image || attachment is DetailContent.Video) {
                            anyFiltered = true
                            index++
                        } else {
                            break
                        }
                    }
                }
                continue
            }
        }
        result += item
        index++
    }

    return if (anyFiltered) result else items
}

@Composable
fun DetailListCompose(
    items: List<DetailContent>,
    searchQuery: String?,
    // 画像/動画取得時の Referer（通常はスレの res/*.htm）
    threadUrl: String? = null,
    modifier: Modifier = Modifier,
    // HTML->プレーンテキストの取得（ViewModelのキャッシュを利用するため注入可能）
    plainTextOf: (DetailContent.Text) -> String = { t -> android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString() },
    plainTextCache: Map<String, String> = emptyMap(),
    // コールバック群 — 従来の DetailAdapter のリスナー相当をComposeで受け取る
    onQuoteClick: ((String) -> Unit)? = null,
    onSodaneClick: ((String) -> Unit)? = null,
    onThreadEndTimeClick: (() -> Unit)? = null,
    onResNumClick: ((resNum: String, resBody: String) -> Unit)? = null,
    onResNumConfirmClick: ((String) -> Unit)? = null,
    onResNumDelClick: ((String) -> Unit)? = null,
    onIdClick: ((String) -> Unit)? = null,
    onBodyClick: ((String) -> Unit)? = null,
    onAddNgFromBody: ((String) -> Unit)? = null,
    // 本文タップで引用元（このレスを引用している投稿）を表示
    onBodyShowBackRefs: ((DetailContent.Text) -> Unit)? = null,
    // ファイル名クリックで引用まとめ
    onFileNameClick: ((String) -> Unit)? = null,
    // "そうだね" 済みかの状態問い合わせ（重複押下の抑止用）
    getSodaneState: ((String) -> Boolean)? = null,
    onImageLoaded: (() -> Unit)? = null,
    onVisibleMaxOrdinal: ((Int) -> Unit)? = null,
    listState: LazyListState? = null,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    initialScrollAnchorId: String? = null,
    itemsVersion: Int = 0,
    bottomPaddingVersion: Int = 0,
    imagesLoadedVersion: Int = 0,
    onSaveScroll: ((Int, Int, String?) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    // Compose側で検索Prev/Nextナビを提供するためのコールバック
    onProvideSearchNavigator: (((() -> Unit), (() -> Unit)) -> Unit)? = null,
    // 上位で保持する "そうだね" 表示カウント（resNum -> count）
    sodaneCounts: Map<String, Int> = emptyMap(),
    onSetSodaneCount: ((String, Int) -> Unit)? = null,
    // スレタイトル（タイトル行を引用扱いにするためのヒント）
    threadTitle: String? = null,
    promptLoadingIds: Set<String> = emptySet(),
    // 自分が投稿したレス番号のセット
    myPostNumbers: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // コールバックは直接受け渡し（レガシーなアダプタ依存は無し）

    // 削除レス非表示設定を読み込み
    val hideDeletedRes = remember { com.valoser.futaburakari.AppPreferences.getHideDeletedRes(context) }
    val hideDuplicateRes = remember { com.valoser.futaburakari.AppPreferences.getHideDuplicateRes(context) }
    val duplicateResThreshold = remember { com.valoser.futaburakari.AppPreferences.getDuplicateResThreshold(context) }

    // 削除レス・重複レスをフィルタリング（各設定が有効な場合のみ）
    val filteredItems = remember(
        items,
        hideDeletedRes,
        hideDuplicateRes,
        duplicateResThreshold,
        plainTextCache,
        plainTextOf
    ) {
        val withoutDeleted = if (hideDeletedRes) {
            items.filter { item ->
                when (item) {
                    is DetailContent.Text -> !isDeletedRes(item)
                    else -> true
                }
            }
        } else {
            items
        }

        val withoutPhantomQuotes = filterPhantomQuoteResponses(
            withoutDeleted,
            plainTextCache,
            plainTextOf
        )

        if (hideDuplicateRes) {
            filterDuplicateResponses(
                withoutPhantomQuotes,
                duplicateResThreshold,
                plainTextCache,
                plainTextOf
            )
        } else {
            withoutPhantomQuotes
        }
    }

    // タップ時のメニュー用一時状態
    // - No. 用（返信/確認）
    var resNumForDialog by remember { mutableStateOf<String?>(null) }
    // - 引用(>) 用（返信/確認）
    var quoteForDialog by remember { mutableStateOf<String?>(null) }
    // - ファイル名 用（返信/確認）
    var fileNameForDialog by remember { mutableStateOf<String?>(null) }
    // - 本文 用（返信/確認/NG）
    var bodyForDialog by remember { mutableStateOf<DetailContent.Text?>(null) }
    // - 行選択ダイアログ用
    var lineSelectionDialog by remember { mutableStateOf<List<String>?>(null) }
    // "そうだね" 表示カウントは親から受け取る

    val initialAnchor = initialScrollAnchorId?.takeIf { it.isNotBlank() }
    val hasInitialAnchor = initialAnchor != null
    val safeIndex = if (hasInitialAnchor) 0 else initialScrollIndex.coerceAtLeast(0)
    val safeOffset = if (hasInitialAnchor) 0 else initialScrollOffset.coerceAtLeast(0)
    val internalState = listState ?: rememberLazyListState(
        initialFirstVisibleItemIndex = safeIndex,
        initialFirstVisibleItemScrollOffset = safeOffset
    )

    var anchorRequestId by remember(initialAnchor) { mutableStateOf(initialAnchor) }
    var lastRestoreSignature by remember(initialAnchor) { mutableStateOf<RestoreSignature?>(null) }
    var lastRestoredAnchorId by remember(initialAnchor) { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var skipNextSave by remember(initialAnchor) { mutableStateOf(false) }
    val saveScrollCallback = rememberUpdatedState(onSaveScroll)

    LaunchedEffect(filteredItems, anchorRequestId, itemsVersion, bottomPaddingVersion, imagesLoadedVersion) {
        val anchorId = anchorRequestId ?: return@LaunchedEffect
        if (filteredItems.isEmpty()) return@LaunchedEffect
        val targetIndex = filteredItems.indexOfFirst { it.id == anchorId }
        if (targetIndex < 0) return@LaunchedEffect

        val signature = RestoreSignature(anchorId, Triple(itemsVersion, bottomPaddingVersion, imagesLoadedVersion))
        if (lastRestoreSignature == signature) return@LaunchedEffect

        val currentIndex = internalState.firstVisibleItemIndex
        val currentOffset = internalState.firstVisibleItemScrollOffset
        val currentAnchor = filteredItems.getOrNull(currentIndex)?.id
        if (currentAnchor == anchorId && currentOffset == safeOffset) {
            lastRestoreSignature = signature
            lastRestoredAnchorId = anchorId
            return@LaunchedEffect
        }

        skipNextSave = true
        restoring = true
        try {
            internalState.scrollToItem(targetIndex, safeOffset)
        } finally {
            restoring = false
        }
        lastRestoreSignature = signature
        lastRestoredAnchorId = anchorId
    }

    // スクロール外の画像を先読み（プリフェッチ）
    // - 現在の可視範囲から前後に数件分のメディア(Image/Video)をCoilへ事前リクエスト
    // - メモリサイズが不明なため、サイズは幅ピクセルを優先（高さは同値でINEXACT精度）
    // - 既に処理したURLは重複プリフェッチを避けるためセットで管理
    run {
        val ctx = LocalContext.current
        val imageLoader = ctx.imageLoader
        val prefetched = remember(filteredItems) { mutableSetOf<String>() }
        val lastPrefetchTime = remember { mutableLongStateOf(0L) }
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        val density = LocalDensity.current
        val screenWidthPx = remember(config.screenWidthDp, density) {
            with(density) { config.screenWidthDp.dp.toPx().toInt().coerceAtLeast(1) }
        }
        val prefetchAhead = 3
        val prefetchBack = 1

        LaunchedEffect(filteredItems, internalState) {
            snapshotFlow { internalState.layoutInfo.visibleItemsInfo }
                .map { vis ->
                    val first = vis.minOfOrNull { it.index } ?: 0
                    val last = vis.maxOfOrNull { it.index } ?: -1
                    first to last
                }
                .distinctUntilChanged()
                .collectLatest { (first, last) ->
                    if (filteredItems.isEmpty()) return@collectLatest

                    val now = System.currentTimeMillis()
                    if (now - lastPrefetchTime.value < 100) return@collectLatest
                    lastPrefetchTime.value = now
                    val startAhead = (last + 1).coerceAtLeast(0)
                    val endAhead = (last + prefetchAhead).coerceAtMost(filteredItems.lastIndex)
                    val startBack = (first - prefetchBack).coerceAtLeast(0)
                    val endBack = (first - 1).coerceAtLeast(-1)

                    fun urlFor(i: Int): String? = when (val c = filteredItems.getOrNull(i)) {
                        is DetailContent.Image -> c.imageUrl
                        is DetailContent.Video -> c.thumbnailUrl ?: c.videoUrl
                        else -> null
                    }

                    // 前方プリフェッチ
                    for (i in startAhead..endAhead) {
                        val url = urlFor(i) ?: continue
                        if (prefetched.add(url)) {
                            val req = createImageRequest(ctx, url, threadUrl, forDisplay = false)
                            imageLoader.enqueue(req)
                        }
                    }

                    // 後方（少しだけ戻り）のプリフェッチ
                    if (endBack >= startBack) {
                        for (i in startBack..endBack) {
                            val url = urlFor(i) ?: continue
                            if (prefetched.add(url)) {
                                val req = createImageRequest(ctx, url, threadUrl, forDisplay = false)
                                imageLoader.enqueue(req)
                            }
                        }
                    }
                }
        }
    }

    // Compose内で検索ヒット位置を計算してナビゲーションを提供
    var hitPositions by remember(filteredItems, searchQuery) { mutableStateOf<List<Int>>(emptyList()) }
    var currentHit by remember(filteredItems, searchQuery) { mutableStateOf(0) }
    LaunchedEffect(filteredItems, searchQuery, plainTextCache) {
        val q = searchQuery?.trim().orEmpty()
        if (q.isBlank()) {
            hitPositions = emptyList()
            currentHit = 0
        } else {
            // 重いのでバックグラウンドで計算
            val list = withContext(kotlinx.coroutines.Dispatchers.Default) {
                val acc = mutableListOf<Int>()
                filteredItems.forEachIndexed { idx, content ->
                    val textToSearch: String? = when (content) {
                        is DetailContent.Text -> plainTextCache[content.id] ?: plainTextOf(content)
                        is DetailContent.Image -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.imageUrl.substringAfterLast('/')}"
                        is DetailContent.Video -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.videoUrl.substringAfterLast('/')}"
                        is DetailContent.ThreadEndTime -> null
                    }
                    if (textToSearch?.contains(q, ignoreCase = true) == true) acc += idx
                }
                acc
            }
            hitPositions = list
            currentHit = if (list.isNotEmpty()) 0 else 0
        }
    }
    val density = LocalDensity.current
    LaunchedEffect(hitPositions) {
        // 上位にハンドラを提供（Prev/Next）。クリック時に該当箇所へアニメーションスクロール。
        onProvideSearchNavigator?.invoke(
            {
                if (hitPositions.isEmpty()) return@invoke
                currentHit = if (currentHit - 1 < 0) hitPositions.lastIndex else currentHit - 1
                val target = hitPositions[currentHit]
                val offsetPx = with(density) { 20.dp.toPx().toInt() }
                scope.launch { internalState.animateScrollToItem(target, offsetPx) }
            },
            {
                if (hitPositions.isEmpty()) return@invoke
                currentHit = if (currentHit + 1 > hitPositions.lastIndex) 0 else currentHit + 1
                val target = hitPositions[currentHit]
                val offsetPx = with(density) { 20.dp.toPx().toInt() }
                scope.launch { internalState.animateScrollToItem(target, offsetPx) }
            }
        )
    }

    // 最大既読序数の通知（50%以上見えている Text 単位の最大序数）
    LaunchedEffect(filteredItems, internalState) {
        snapshotFlow { internalState.layoutInfo }
            .map { info ->
                val vpStart = info.viewportStartOffset
                val vpEnd = info.viewportEndOffset
                val visible = info.visibleItemsInfo
                var maxOrdinal = 0
                for (vi in visible) {
                    val top = vi.offset
                    val bottom = vi.offset + vi.size
                    val visibleTop = maxOf(vpStart, top)
                    val visibleBottom = minOf(vpEnd, bottom)
                    val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0)
                    val ratio = if (vi.size > 0) visibleHeight.toFloat() / vi.size.toFloat() else 0f
                    if (ratio >= 0.5f) {
                        val ordinal = ordinalForIndex(filteredItems, vi.index)
                        if (ordinal > maxOrdinal) maxOrdinal = ordinal
                    }
                }
                // 補正：最終要素がほぼ見えていれば末尾まで既読にしやすく
                if (visible.isNotEmpty()) {
                    val lastIdx = filteredItems.lastIndex
                    val lastOrdinal = ordinalForIndex(filteredItems, lastIdx)
                    val lastVi = visible.last()
                    if (lastVi.index >= lastIdx - 1 && lastOrdinal > maxOrdinal) maxOrdinal = lastOrdinal
                }
                maxOrdinal
            }
            .distinctUntilChanged()
            .collectLatest { ord -> if (ord > 0) onVisibleMaxOrdinal?.invoke(ord) }
    }

    // スクロール位置の変化を親へ保存通知（index + offset） — 復元直後は一時的に抑制
    LaunchedEffect(filteredItems, internalState, anchorRequestId) {
        coroutineScope {
            var lastSnapshot: ScrollSnapshot? = null
            var lastSavedSnapshot: ScrollSnapshot? = null

            fun maybeSaveSnapshot(snapshot: ScrollSnapshot) {
                val saver = saveScrollCallback.value ?: return
                if (snapshot == lastSavedSnapshot) return
                if (skipNextSave) {
                    skipNextSave = false
                    return
                }
                if (restoring) return
                saver(snapshot.index, snapshot.offset, snapshot.anchorId)
                lastSavedSnapshot = snapshot
            }

            launch {
                snapshotFlow {
                    val index = internalState.firstVisibleItemIndex
                    val offset = internalState.firstVisibleItemScrollOffset
                    val anchorId = filteredItems.getOrNull(index)?.id
                    ScrollSnapshot(index, offset, anchorId)
                }
                    .distinctUntilChanged()
                    .collect { snapshot ->
                        lastSnapshot = snapshot
                        val restoredAnchor = lastRestoredAnchorId
                        val requestAnchor = anchorRequestId
                        if (!restoring && restoredAnchor != null && requestAnchor == restoredAnchor && snapshot.anchorId != requestAnchor) {
                            anchorRequestId = null
                        }
                        if (!internalState.isScrollInProgress) {
                            maybeSaveSnapshot(snapshot)
                        }
                    }
            }

            launch {
                snapshotFlow { internalState.isScrollInProgress }
                    .distinctUntilChanged()
                    .collectLatest { inProgress ->
                        if (!inProgress) {
                            delay(32L)
                            val snapshot = lastSnapshot ?: run {
                                val index = internalState.firstVisibleItemIndex
                                val offset = internalState.firstVisibleItemScrollOffset
                                val anchorId = filteredItems.getOrNull(index)?.id
                                ScrollSnapshot(index, offset, anchorId)
                            }
                            maybeSaveSnapshot(snapshot)
                        }
                    }
            }
        }
    }

    LazyColumn(state = internalState, modifier = modifier.fillMaxWidth(), contentPadding = contentPadding) {
        itemsIndexed(filteredItems, key = { index, it -> stableKey(it, index) }) { index, item ->
            when (item) {
                is DetailContent.Text -> {
                    val plainState = produceState<String?>(
                        initialValue = plainTextCache[item.id],
                        key1 = item.id,
                        key2 = plainTextCache[item.id]
                    ) {
                        val cached = plainTextCache[item.id]
                        if (cached != null) {
                            value = cached
                        } else {
                            value = withContext(kotlinx.coroutines.Dispatchers.Default) { plainTextOf(item) }
                        }
                    }
                    val plain = plainState.value.orEmpty()
                    val selfResNum = remember(plain) {
                        // No 抽出を寛容に（ドット任意・全角許容・空白改行許容）
                        Regex("""(?i)No[.\uFF0E]?\s*(\n?\s*)?(\d+)""").find(plain)?.groupValues?.getOrNull(2)
                            ?: Regex("""(?i)No[.\uFF0E]?\s*(\d+)""").find(plain)?.groupValues?.getOrNull(1)
                    }
                    // 楽観表示の変更に追随するよう、エントリのスナップショットをキーに再計算
                    // 表示用にトークン周りの空白を補正し、そうだねの楽観カウントを適用
                    val displayText = remember(plain, sodaneCounts.toList()) {
                        // 楽観表示の適用時、行内で No が見つからない場合は自投稿の No をフォールバック
                        applySodaneDisplay(padTokensForSpacingCached(plain), sodaneCounts, selfResNum)
                    }
                    // クリック可能領域（No./引用/ID/URL/ファイル名/そうだね/検索ハイライト）を付与
                    val annotated = remember(displayText, searchQuery, threadTitle, myPostNumbers) { buildAnnotatedFromText(displayText, searchQuery, threadTitle, myPostNumbers) }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 短押しは子の ClickableText に渡す。ここでは「長押しのみ」を本文引用として扱う。
                            .pointerInput(plain) {
                                detectTapGestures(
                                    onLongPress = {
                                        val bodyOnly = extractBodyOnlyPlain(plain)
                                        val source = if (bodyOnly.isNotBlank()) bodyOnly else plain
                                        val quoted = source.lines().joinToString("\n") { ">" + it }
                                        onBodyClick?.invoke(quoted)
                                    }
                                )
                            }
                    ) {
                        ClickableText(
                            text = annotated,
                            // 明示的にテーマの文字色を適用して、ダークモードでの黒固定を回避
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                            ),
                            onClick = { offset ->
                            val tags = annotated.getStringAnnotations(start = offset, end = offset)
                            val res = tags.firstOrNull { it.tag == "res" }?.item
                            val filename = tags.firstOrNull { it.tag == "filename" }?.item
                            val quote = tags.firstOrNull { it.tag == "quote" }?.item
                            val id = tags.firstOrNull { it.tag == "id" }?.item
                            val url = tags.firstOrNull { it.tag == "url" }?.item
                            val sodane = tags.firstOrNull { it.tag == "sodane" }?.item
                            when {
                                // No. タップ: メニュー（返信 / 確認）
                                res != null -> { resNumForDialog = res }
                                // ファイル名タップ: メニュー（返信 / 確認）
                                filename != null -> { fileNameForDialog = filename }
                                // 引用(>)タップ: メニュー（返信 / 確認）
                                quote != null -> { quoteForDialog = quote }
                                id != null -> onIdClick?.invoke(id)
                                url != null -> try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                                sodane != null -> {
                                    // 推定対象 No. を同一行から取得（なければ投稿自身の No. をフォールバック）
                                    val lineStart = displayText.lastIndexOf('\n', startIndex = offset.coerceAtMost(displayText.length), ignoreCase = false).let { if (it < 0) 0 else it + 1 }
                                    val lineEnd = displayText.indexOf('\n', startIndex = lineStart).let { if (it < 0) displayText.length else it }
                                    val lineText = displayText.substring(lineStart, lineEnd)
                                    val m = Regex("""(?i)No[.\uFF0E]?\s*(\n?\s*)?(\d+)""").find(lineText)
                                    val rn = m?.groupValues?.getOrNull(2)
                                    val target = rn ?: selfResNum
                                    if (!target.isNullOrBlank()) {
                                        // 既に押していれば無視
                                        val disabled = getSodaneState?.invoke(target) ?: false
                                        if (!disabled) {
                                            // 楽観的に +1 表示（親に委譲）
                                            val next = (sodaneCounts[target] ?: 0) + 1
                                            onSetSodaneCount?.invoke(target, next)
                                            // コールバック（サーバ送信）
                                            onSodaneClick?.invoke(target)
                                        }
                                    }
                                }
                            }
                            // 本文（どのタグにも該当しない領域）タップ: メニュー（返信 / 確認 / NG）
                            if (res == null && filename == null && quote == null && id == null && url == null && sodane == null) {
                                bodyForDialog = item
                            }
                            }
                        )
                    }
                }

                is DetailContent.Image -> {
                    val ctx = LocalContext.current
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 空のimageUrlの場合は直接「画像なし」を表示
                        if (item.imageUrl.isBlank()) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .background(
                                        androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    )
                            ) {
                                androidx.compose.material3.Text(
                                    text = "画像なし",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                                )
                            }
                        } else {
                            coil3.compose.SubcomposeAsyncImage(
                                model = createImageRequest(ctx, item.imageUrl, threadUrl, forDisplay = true),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val i = android.content.Intent(ctx, com.valoser.futaburakari.MediaViewActivity::class.java).apply {
                                            putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_TYPE, com.valoser.futaburakari.MediaViewActivity.TYPE_IMAGE)
                                            putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_URL, item.imageUrl)
                                            putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_TEXT, item.prompt)
                                            threadUrl?.let { putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_REFERER, it) }
                                        }
                                        ctx.startActivity(i)
                                    },
                                contentScale = ContentScale.Fit,
                                loading = {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                    ) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                                        )
                                    }
                                },
                                error = {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp)
                                            .background(
                                                androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                            )
                                    ) {
                                        androidx.compose.material3.Text(
                                            text = "画像なし",
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                                        )
                                    }
                                },
                                onSuccess = { onImageLoaded?.invoke() }
                            )
                        }
                        // プロンプトは ViewModel 側でプレーン化済み。長文はタップで展開/折りたたみ。
                        val promptText = item.prompt?.trim()?.takeIf { it.isNotEmpty() }
                        val isPromptLoading = promptLoadingIds.contains(item.id)
                        if (isPromptLoading && promptText == null) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .padding(horizontal = LocalSpacing.current.m, vertical = LocalSpacing.current.xs),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.height(16.dp).width(16.dp),
                                    strokeWidth = 2.dp
                                )
                                androidx.compose.material3.Text(
                                    text = "読み込み中…",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = LocalSpacing.current.xs)
                                )
                            }
                        }
                        if (promptText != null) {
                            var expanded by remember(item.id) { mutableStateOf(false) }
                            SelectionContainer {
                                androidx.compose.material3.Text(
                                    text = promptText,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                                    overflow = if (expanded) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(horizontal = LocalSpacing.current.m, vertical = LocalSpacing.current.xs)
                                        .clickable { expanded = !expanded }
                                )
                            }
                        }
                    }
                }

                is DetailContent.Video -> {
                    val ctx = LocalContext.current
                    val previewUrl = item.thumbnailUrl ?: item.videoUrl
                    Column(modifier = Modifier.fillMaxWidth()) {
                        coil3.compose.SubcomposeAsyncImage(
                            model = createImageRequest(ctx, previewUrl, threadUrl, forDisplay = true),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val i = android.content.Intent(ctx, com.valoser.futaburakari.MediaViewActivity::class.java).apply {
                                        putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_TYPE, com.valoser.futaburakari.MediaViewActivity.TYPE_VIDEO)
                                        putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_URL, item.videoUrl)
                                        threadUrl?.let { putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_REFERER, it) }
                                    }
                                    ctx.startActivity(i)
                                },
                            contentScale = ContentScale.Fit,
                            loading = {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                                    )
                                }
                            },
                            error = {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .background(
                                            androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                                            androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                        )
                                ) {
                                    androidx.compose.material3.Text(
                                        text = "動画なし",
                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
                                    )
                                }
                            },
                            onSuccess = { onImageLoaded?.invoke() }
                        )
                        // サムネイル下の説明テキストは ViewModel 側でプレーン化済み。長文はタップで展開/折りたたみ。
                        val promptText = item.prompt?.trim()?.takeIf { it.isNotEmpty() }
                        val isPromptLoading = promptLoadingIds.contains(item.id)
                        if (isPromptLoading && promptText == null) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .padding(horizontal = LocalSpacing.current.m, vertical = LocalSpacing.current.xs),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.height(16.dp).width(16.dp),
                                    strokeWidth = 2.dp
                                )
                                androidx.compose.material3.Text(
                                    text = "読み込み中…",
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = LocalSpacing.current.xs)
                                )
                            }
                        }
                        if (promptText != null) {
                            var expanded by remember(item.id) { mutableStateOf(false) }
                            SelectionContainer {
                                androidx.compose.material3.Text(
                                    text = promptText,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                                    overflow = if (expanded) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(horizontal = LocalSpacing.current.m, vertical = LocalSpacing.current.xs)
                                        .clickable { expanded = !expanded }
                                )
                            }
                        }
                    }
                }

                is DetailContent.ThreadEndTime -> {
                    androidx.compose.material3.Text(
                        text = item.endTime,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = LocalSpacing.current.m, end = LocalSpacing.current.m, top = LocalSpacing.current.s, bottom = LocalSpacing.current.l)
                            .clickable { onThreadEndTimeClick?.invoke() },
                    )
                }
            }

            // Divider: ブロック末尾でのみ描画（次要素がText/EndTime or 末尾）
            if (isEndOfBlock(filteredItems, index)) {
                // 視認性のための余白を上下に付与し、コンテンツと線が密着しないようにする
                // ダークモードでも見やすいよう太くalpha値を高めに設定
                androidx.compose.material3.Divider(
                    modifier = Modifier.padding(vertical = LocalSpacing.current.s),
                    thickness = 2.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    // No. タップメニュー（返信 / 確認 / 削除）
    resNumForDialog?.let { resDialog ->
        AlertDialog(
            onDismissRequest = { resNumForDialog = null },
            title = { androidx.compose.material3.Text("No.$resDialog") },
            text = { androidx.compose.material3.Text("操作を選択してください") },
            confirmButton = {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = {
                        onResNumClick?.invoke(resDialog, ">No.$resDialog")
                        resNumForDialog = null
                    }) { androidx.compose.material3.Text("返信") }
                    TextButton(onClick = {
                        onResNumConfirmClick?.invoke(resDialog)
                        resNumForDialog = null
                    }) { androidx.compose.material3.Text("確認") }
                    onResNumDelClick?.let { handleDelete ->
                        TextButton(onClick = {
                            handleDelete(resDialog)
                            resNumForDialog = null
                        }) { androidx.compose.material3.Text("削除") }
                    }
                }
            }
        )
    }

    // ファイル名タップメニュー（返信 / 確認）
    fileNameForDialog?.let { fn ->
        AlertDialog(
            onDismissRequest = { fileNameForDialog = null },
            title = { androidx.compose.material3.Text(fn) },
            text = { androidx.compose.material3.Text("操作を選択してください") },
            confirmButton = {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = {
                        onBodyClick?.invoke(">" + fn)
                        fileNameForDialog = null
                    }) { androidx.compose.material3.Text("返信") }
                    TextButton(onClick = {
                        onFileNameClick?.invoke(fn)
                        fileNameForDialog = null
                    }) { androidx.compose.material3.Text("確認") }
                }
            }
        )
    }

    // 引用タップメニュー（返信 / 確認）
    quoteForDialog?.let { qt ->
        AlertDialog(
            onDismissRequest = { quoteForDialog = null },
            title = { androidx.compose.material3.Text("引用") },
            text = { androidx.compose.material3.Text("操作を選択してください") },
            confirmButton = {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = {
                        val replyText = ">" + qt
                        onBodyClick?.invoke(replyText)
                        quoteForDialog = null
                    }) { androidx.compose.material3.Text("返信") }
                    TextButton(onClick = {
                        onQuoteClick?.invoke(qt)
                        quoteForDialog = null
                    }) { androidx.compose.material3.Text("確認") }
                }
            }
        )
    }

    // 本文タップメニュー（返信 / 確認 / NG / 選択して引用）
    bodyForDialog?.let { src ->
        val plainState = produceState<String?>(
            initialValue = plainTextCache[src.id],
            key1 = src.id,
            key2 = plainTextCache[src.id]
        ) {
            val cached = plainTextCache[src.id]
            if (cached != null) {
                value = cached
            } else {
                value = withContext(kotlinx.coroutines.Dispatchers.Default) { plainTextOf(src) }
            }
        }
        val plain = plainState.value.orEmpty()
        val bodyOnly = extractBodyOnlyPlain(plain)
        val source = if (bodyOnly.isNotBlank()) bodyOnly else plain
        val quoted = source.lines().joinToString("\n") { ">" + it }
        val lines = source.lines().filter { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { bodyForDialog = null },
            title = { androidx.compose.material3.Text("本文") },
            text = { androidx.compose.material3.Text("操作を選択してください") },
            confirmButton = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(onClick = {
                            onBodyClick?.invoke(quoted)
                            bodyForDialog = null
                        }) { androidx.compose.material3.Text("返信") }
                        TextButton(onClick = {
                            lineSelectionDialog = lines
                            bodyForDialog = null
                        }) { androidx.compose.material3.Text("選択") }
                    }
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(onClick = {
                            onBodyShowBackRefs?.invoke(src)
                            bodyForDialog = null
                        }) { androidx.compose.material3.Text("確認") }
                        TextButton(onClick = {
                            onAddNgFromBody?.invoke(plain)
                            bodyForDialog = null
                        }) { androidx.compose.material3.Text("NG") }
                    }
                }
            }
                )
    }

    // 行選択ダイアログ
    lineSelectionDialog?.let { lines ->
        val selectedLines = remember { mutableStateMapOf<Int, Boolean>().apply {
            lines.indices.forEach { this[it] = true }
        } }
        AlertDialog(
            onDismissRequest = { lineSelectionDialog = null },
            title = { androidx.compose.material3.Text("引用する行を選択") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(lines.size) { index ->
                        val line = lines[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedLines[index] = !(selectedLines[index] ?: true) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = selectedLines[index] ?: true,
                                onCheckedChange = { selectedLines[index] = it }
                            )
                            androidx.compose.material3.Text(
                                text = line,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selected = lines.filterIndexed { index, _ -> selectedLines[index] == true }
                    val quoted = selected.joinToString("\n") { ">" + it }
                    onBodyClick?.invoke(quoted)
                    lineSelectionDialog = null
                }) {
                    androidx.compose.material3.Text("引用")
                }
            },
            dismissButton = {
                TextButton(onClick = { lineSelectionDialog = null }) {
                    androidx.compose.material3.Text("キャンセル")
                }
            }
        )
    }

}

/**
 * 指定インデックスまでに現れた本文（Text）要素の個数を返す。
 * これが投稿の序数（1-based の表示順）に相当する。
 */
private fun ordinalForIndex(all: List<DetailContent>, index: Int): Int {
    var ord = 0
    var i = 0
    while (i <= index && i < all.size) {
        if (all[i] is DetailContent.Text) ord++
        i++
    }
    return ord
}

/**
 * ヘッダ風の行（No./ID 行など）を除いた「本文のみ」のプレーンテキストを抽出する。
 * - 先頭から以下をスキップして本文開始位置を決定: ID 行／No 行／日付時刻を含む行／
 *   ファイル情報行（ファイル名/画像/拡張子付きの情報行）／空行。
 * - 引用行(>)も本文に含める（選択肢に表示するため）。
 * - 画像・動画のサイズ表示行（[123456 B]や xxx.jpg-(123456 B) など）を除外。
 * - 決定した行から末尾までを本文として返す（末尾の空行は削除）。
 */
private fun extractBodyOnlyPlain(plain: String): String {
    fun normalize(s: String): String = java.text.Normalizer.normalize(
        s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
        java.text.Normalizer.Form.NFKC
    )
    val idPat = Regex("""(?i)\bID(?:[:：]|無し)\b[\w./+\-]*""")
    val noPat = Regex("""(?i)\b(?:No|Ｎｏ)[\.\uFF0E]?\s*\d+\b""")
    val dateTimePat = Regex("""(?:(?:\d{2}|\d{4})/\d{1,2}/\d{1,2}).*?\d{1,2}:\d{2}:\d{2}""")
    val fileInfoHeadPat = Regex("""(?i)^\s*(?:ファイル名|画像|ファイル)[:：].*""")
    val ext = "(?:jpg|jpeg|png|gif|webp|bmp|mp4|webm|avi|mov|mkv)"
    // 例: foo.jpg - (123KB 800x600) / foo.png(12.3MB) など
    val fileInfoGenericPat = Regex("""(?i)^\s*.*?\.$ext\s*[\-ー－]?\s*\([^)]*\).*""")
    // 画像・動画サイズ表示パターン: [180986 B] や xxx.jpg-(180986 B) など
    val fileSizePat = Regex("""^\s*(?:\[[\d\s]+[KMGT]?B\]|.*?[\-ー－]\([\d\s]+[KMGT]?B\))\s*$""")
    val lines = plain.lines()
    var start = 0
    while (start < lines.size) {
        val raw = lines[start]
        val trimmed = raw.trimStart()
        if (trimmed.isBlank()) { start++; continue }
        val norm = normalize(trimmed)
        val isHeader = idPat.containsMatchIn(norm) || noPat.containsMatchIn(norm) ||
                dateTimePat.containsMatchIn(norm) || fileInfoHeadPat.containsMatchIn(norm) ||
                fileInfoGenericPat.containsMatchIn(norm) || fileSizePat.containsMatchIn(trimmed)
        // 引用行も本文に含める（isLeadQuoteのチェックを削除）
        if (isHeader) { start++; continue }
        break
    }
    val kept = lines.drop(start).filterNot { line ->
        val trimmed = line.trim()
        // 本文中のサイズ表示行も除外
        fileSizePat.containsMatchIn(trimmed)
    }
    // 末尾の空行は削除
    return kept.dropLastWhile { it.isBlank() }.joinToString("\n")
}

/**
 * 本文テキストから AnnotatedString を構築。
 * - クリック可能: No.xxxx、引用行(>／全角＞)、スレタイ行、ID:xxxx、URL、ファイル名（xxx.jpg 等）、そうだねトークン。
 * - 検索語は背景色でハイライト。
 * - 自レス番号は強調色で表示。
 */
private fun buildAnnotatedFromText(text: String, highlight: String?, threadTitle: String?, myPostNumbers: Set<String> = emptySet()): AnnotatedString = buildAnnotatedString {
    append(text)

    // ヘッダー行を識別（日付時刻パターンを含む行のみ、または数字+無念+Name等のメタデータを含む最初の行のみ）
    fun isHeaderLine(line: String, lineIndex: Int): Boolean {
        val trimmed = line.trim()

        // 日付時刻パターンがある場合は確実にヘッダー行
        val hasDateTime = Regex("""\d{2}/\d{2}/\d{2}\(\S+\)\d{2}:\d{2}:\d{2}""").containsMatchIn(trimmed)
        if (hasDateTime) return true

        // 最初の行で、投稿番号+無念+Name+としあき のようなメタデータパターンがある場合のみ
        if (lineIndex == 0) {
            val hasPostNumber = Regex("""^\d+""").containsMatchIn(trimmed)
            val hasName = Regex("""(?:無念|Name|としあき)""").containsMatchIn(trimmed)
            val hasNo = Regex("""(?i)No[.\uFF0E]?\s*\d+""").containsMatchIn(trimmed)
            return hasPostNumber && hasName && hasNo
        }

        return false
    }

    // No.1234 pattern（ドット任意・全角ドット・空白許容）
    val resRegex = Regex("""(?i)No[.\uFF0E]?\s*(\d+)""")
    resRegex.findAll(text).forEach { m ->
        // マッチした位置が含まれる行を特定
        val matchStart = m.range.first
        val lineStart = text.lastIndexOf('\n', matchStart).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', matchStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)

        // 行インデックスを計算
        val lineIndex = text.substring(0, lineStart).count { it == '\n' }

        // ヘッダー行内のNo.または引用行内のNo.をクリック可能にする
        val trimmedLine = line.trimStart()
        val isQuoteLine = trimmedLine.startsWith(">") || trimmedLine.startsWith("＞")
        if (isHeaderLine(line, lineIndex) || isQuoteLine) {
            val num = m.groupValues[1]
            // 自レス番号なら強調色、それ以外は下線のみ
            if (myPostNumbers.contains(num)) {
                addStyle(SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    color = Color(0xFF4CAF50) // 緑色で強調
                ), m.range.first, m.range.last + 1)
            } else {
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
            }
            addStringAnnotation(tag = "res", annotation = num, start = m.range.first, end = m.range.last + 1)
        }
    }
    // 引用行: 行頭の空白や全角＞を許容し、タグには正規化したトークンを渡す
    // 視認性向上のため背景色を追加
    val lineRegex = Regex("^(?:[\\t \\u3000])*[>＞]+[^\\n]*", RegexOption.MULTILINE)
    lineRegex.findAll(text).forEach { m ->
        val tokenRaw = m.value
        val token = tokenRaw.trimStart().replace('＞', '>')
        val start = m.range.first + (m.value.length - tokenRaw.trimStart().length)
        val end = m.range.last + 1
        addStyle(SpanStyle(
            textDecoration = TextDecoration.Underline,
            background = Color(0x1A9C27B0) // 薄い紫色の背景
        ), start, end)
        addStringAnnotation(tag = "quote", annotation = token, start = start, end = end)
    }
    // タイトル行: スレタイと一致する行（空白/全角差を無視）は引用としてもクリック可能にする
    if (!threadTitle.isNullOrBlank()) {
        fun normalize(s: String): String = java.text.Normalizer.normalize(
            s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
            java.text.Normalizer.Form.NFKC
        ).replace(Regex("\\s+"), " ").trim()
        val needle = normalize(threadTitle)
        var idx = 0
        while (idx <= text.length) {
            val nl = text.indexOf('\n', idx)
            val end = if (nl < 0) text.length else nl
            val s = idx
            val e = end
            val line = text.substring(s, e)
            val trimmed = line.trim()
            // 既に '>' で始まる行は通常の引用検出に任せる
            if (!trimmed.startsWith('>')) {
                val norm = normalize(line)
                if (norm.isNotBlank() && norm == needle) {
                    val token = ">" + trimmed
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, e)
                    addStringAnnotation(tag = "quote", annotation = token, start = s, end = e)
                }
            }
            if (nl < 0) break else idx = nl + 1
        }
    }
    // ID:xxxx pattern
    val idRegex = Regex("""ID([:：])([\u0021-\u007E\u00A0-\u00FF\w./+]+)""")
    idRegex.findAll(text).forEach { m ->
        val id = m.groupValues.getOrNull(2) ?: return@forEach
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation(tag = "id", annotation = id, start = m.range.first, end = m.range.last + 1)
    }
    // 検索ハイライト
    if (!highlight.isNullOrBlank()) {
        val pat = Regex(Regex.escape(highlight), RegexOption.IGNORE_CASE)
        pat.findAll(text).forEach { f ->
            addStyle(SpanStyle(background = Color.Yellow), f.range.first, f.range.last + 1)
        }
    }
    // URL: クリック可能にする
    val urlRegex = Patterns.WEB_URL.toRegex()
    urlRegex.findAll(text).forEach { m ->
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation("url", m.value, m.range.first, m.range.last + 1)
    }
    // ファイル名トークン（拡張子を含むものを検出しクリック可能に）
    run {
        val ext = "(?:jpg|jpeg|png|gif|webp|bmp|mp4|webm|avi|mov|mkv)"
        val pat = Regex("""(?i)([A-Za-z0-9._-]+\.$ext)""")
        pat.findAll(text).forEach { m ->
            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
            addStringAnnotation("filename", m.groupValues[1], m.range.first, m.range.last + 1)
        }
    }
    // そうだねトークン（+ / ＋ / そうだね / そうだねxN）— ヘッダー行のみ、引用行を除き、No.の直後のみ対象（IDより前のみ）
    val sodaneRegex = Regex("""(?:そうだねx\d+|そうだね|[+＋])""")
    var start = 0
    var lineIndex = 0
    while (start <= text.length) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        val lineStart = start
        val line = text.substring(lineStart, end)
        val trimmed = line.trimStart()
        val isQuote = trimmed.startsWith(">")

        // ヘッダー行で引用でない場合のみ処理
        if (!isQuote && isHeaderLine(line, lineIndex)) {
            // No.パターンを検出（行頭限定を解除）
            val noRegex = Regex("""(?i)No[.\uFF0E]?\s*(\n?\s*)?(\d+)""")

            // 行内の全てのNo.パターンを検出
            noRegex.findAll(line).forEach { noMatch ->
                // No.の後、次のトークン（ID:など）までの範囲を確認
                val afterNoStartInLine = noMatch.range.last + 1
                if (afterNoStartInLine < line.length) {
                    val afterNo = line.substring(afterNoStartInLine)

                    // IDパターンを検出して、それより前の範囲に限定
                    val idMatch = Regex("""ID[:：]""").find(afterNo)
                    val searchEnd = idMatch?.range?.first ?: afterNo.length
                    val searchRange = afterNo.substring(0, searchEnd)

                    // 最初のそうだねトークンのみを対象にする
                    sodaneRegex.find(searchRange)?.let { sodaneMatch ->
                        val s = lineStart + afterNoStartInLine + sodaneMatch.range.first
                        val e = lineStart + afterNoStartInLine + sodaneMatch.range.last + 1
                        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, e)
                        addStringAnnotation("sodane", "1", s, e)
                    }
                }
            }
        }
        if (nl < 0) break else { start = nl + 1; lineIndex++ }
    }
}

// 文字列処理結果をキャッシュ
private val stringProcessingCache = LruCache<String, String>(100)

/**
 * プレーンテキスト上で詰まりやすいトークン（ID／No／+／そうだね）の間に空白を補正し、
 * ヘッダー行かつ非引用行で No. を含む場合に行末へ そうだね トークンが無ければ付与する。
 */
private fun padTokensForSpacingCached(src: String): String {
    return stringProcessingCache.get(src) ?: run {
        val result = padTokensForSpacing(src)
        stringProcessingCache.put(src, result)
        result
    }
}

private fun padTokensForSpacing(src: String): String {
    var t = src.replace("\u200B", "")
    // 表記ゆれ吸収: 全角→半角などを正規化し、半角スペースに統一
    t = java.text.Normalizer.normalize(t.replace('　', ' '), java.text.Normalizer.Form.NFKC)
    // 日付や括弧閉じの直後に No が隣接してしまうケースを緩和（例: "...12:34:56)No.1234" → ") No.1234"）
    // 数字または ')' の直後に No が続く場合にスペースを補う。
    t = Regex("""([0-9)])\s*(?=No[.\uFF0E]?)""", RegexOption.IGNORE_CASE).replace(t, "$1 ")
    // 汎用: 非空白直後に No が続く場合はスペースを補う（ID と No が隣接しているケース等の取りこぼしを補完）
    t = Regex("""(?i)(?<=\S)(?=No[.\uFF0E]?\s*\d+)""").replace(t, " ")
    // ID:xxxx と No.xxxx の間に順不同で必ず空白を入れる（No のドット・全角ドット・空白を許容）
    t = Regex("""(?i)(ID[:：][\\w./+\-]+)\s*(?=No[.\uFF0E]?)""", RegexOption.IGNORE_CASE).replace(t, "$1 ")
    t = Regex("""(?i)(No[.\uFF0E]?\s*\d+)\s*(?=ID[:：])""", RegexOption.IGNORE_CASE).replace(t, "$1 ")
    // No.xxxx と +/そうだね トークンの間に空白を入れる（No のドット・全角ドット・空白を許容）
    t = Regex("""(No[.\uFF0E]?\s*\d+)(?=(?:[+＋]|そうだね))""").replace(t, "$1 ")
    // 複数の空白を1つに正規化
    t = Regex("[ ]{2,}").replace(t, " ")
    // ヘッダー行を識別する関数（buildAnnotatedFromTextと同じ）
    fun isHeaderLine(line: String, lineIndex: Int): Boolean {
        val trimmed = line.trim()

        // 日付時刻パターンがある場合は確実にヘッダー行
        val hasDateTime = Regex("""\d{2}/\d{2}/\d{2}\(\S+\)\d{2}:\d{2}:\d{2}""").containsMatchIn(trimmed)
        if (hasDateTime) return true

        // 最初の行で、投稿番号+無念+Name+としあき のようなメタデータパターンがある場合のみ
        if (lineIndex == 0) {
            val hasPostNumber = Regex("""^\d+""").containsMatchIn(trimmed)
            val hasName = Regex("""(?:無念|Name|としあき)""").containsMatchIn(trimmed)
            val hasNo = Regex("""(?i)No[.\uFF0E]?\s*\d+""").containsMatchIn(trimmed)
            return hasPostNumber && hasName && hasNo
        }

        return false
    }

    // No. を含む非引用行の末尾に そうだね トークンが無ければ付与（IDより前の位置のみチェック）
    // ただし、ヘッダー行のみに適用する
    val sb = StringBuilder()
    var start = 0
    var lineIndex = 0
    while (start < t.length) {
        val nl = t.indexOf('\n', start)
        val end = if (nl < 0) t.length else nl
        val line = t.substring(start, end)
        val trimmed = line.trimStart()
        val isQuote = trimmed.startsWith(">")
        // No. を行内どこでも許容（行頭限定を解除）
        val hasNo = Regex("""(?i)\bNo[.\uFF0E]?\s*\d+\b""").containsMatchIn(trimmed)

        // ヘッダー行で、引用でなくNo.を含む場合に「そうだね」を追加
        if (isHeaderLine(line, lineIndex) && !isQuote && hasNo) {
            // No.の直後に「そうだね」を挿入する
            val noPattern = Regex("""(?i)(No[.\uFF0E]?\s*\d+)""")
            val noMatch = noPattern.find(line)

            if (noMatch != null) {
                val beforeNo = line.substring(0, noMatch.range.first)
                val noText = noMatch.value
                val afterNo = line.substring(noMatch.range.last + 1)

                // No.の後に既にそうだねがあるかチェック
                if (!Regex("""^\s*(?:[+＋]|そうだね(?:x\d+)?)""").containsMatchIn(afterNo)) {
                    // No.の直後に「そうだね」を挿入
                    sb.append(beforeNo).append(noText).append(" そうだね").append(afterNo)
                } else {
                    sb.append(line)
                }
            } else {
                sb.append(line)
            }
        } else sb.append(line)

        if (nl >= 0) sb.append('\n')
        start = if (nl < 0) t.length else nl + 1
        lineIndex++
    }
    return sb.toString()
}

/**
 * 「そうだね」の楽観表示を適用してテキストを上書きする。
 * - そうだねトークン（+／＋／そうだね／そうだねxN）を「そうだねxN」に置換。
 * - 行内から No を抽出できない場合は selfResNum をフォールバックとして使用。
 * - ヘッダー行のみに適用する。
 */
private fun applySodaneDisplay(text: String, overrides: Map<String, Int>, selfResNum: String?): String {
    if (overrides.isEmpty()) return text

    // ヘッダー行を識別する関数（buildAnnotatedFromTextと同じ）
    fun isHeaderLine(line: String, lineIndex: Int): Boolean {
        val trimmed = line.trim()

        // 日付時刻パターンがある場合は確実にヘッダー行
        val hasDateTime = Regex("""\d{2}/\d{2}/\d{2}\(\S+\)\d{2}:\d{2}:\d{2}""").containsMatchIn(trimmed)
        if (hasDateTime) return true

        // 最初の行で、投稿番号+無念+Name+としあき のようなメタデータパターンがある場合のみ
        if (lineIndex == 0) {
            val hasPostNumber = Regex("""^\d+""").containsMatchIn(trimmed)
            val hasName = Regex("""(?:無念|Name|としあき)""").containsMatchIn(trimmed)
            val hasNo = Regex("""(?i)No[.\uFF0E]?\s*\d+""").containsMatchIn(trimmed)
            return hasPostNumber && hasName && hasNo
        }

        return false
    }

    val sb = StringBuilder(text.length + 100) // 事前サイズ指定
    var start = 0
    var lineIndex = 0
    while (start < text.length) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        var line = text.substring(start, end)

        // ヘッダー行のみに楽観表示を適用
        if (isHeaderLine(line, lineIndex)) {
            // No の抽出も寛容に（ドット任意・全角許容・空白改行許容）
            val m = Regex("""(?i)No[.\uFF0E]?\s*(\d+)""").find(line)
            val rn = m?.groupValues?.getOrNull(1) ?: selfResNum
            val cnt = rn?.let { overrides[it] }
            if (cnt != null && cnt > 0) {
                line = line.replace(Regex("(?:そうだねx\\d+|そうだね|[+＋])"), "そうだねx$cnt")
            }
        }

        sb.append(line)
        if (nl >= 0) sb.append('\n')
        start = if (nl < 0) text.length else nl + 1
        lineIndex++
    }
    return sb.toString()
}

/**
 * Text + (Image|Video)* のブロックの区切りかを判定する。
 * 次要素が Text/ThreadEndTime/末尾 の場合に区切りとみなし、末尾(null)では線を描かない。
 */
private fun isEndOfBlock(items: List<DetailContent>, index: Int): Boolean {
    if (index !in items.indices) return false
    val next = items.getOrNull(index + 1)
    // ブロック構造: Text + (Image|Video)* の塊。次がText/EndTime/なし なら区切り。
    // 末尾(null)では線を描かないように変更（最終行の下に線は不要）
    return when (next) {
        null -> false
        is DetailContent.Text, is DetailContent.ThreadEndTime -> true
        is DetailContent.Image, is DetailContent.Video -> false
    }
}
