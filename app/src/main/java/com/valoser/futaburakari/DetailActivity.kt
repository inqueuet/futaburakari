package com.valoser.futaburakari

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Html
 
 
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
// import androidx.appcompat.app.AppCompatActivity
 
import androidx.activity.viewModels
import androidx.lifecycle.Observer
 
 
import androidx.activity.compose.setContent
 
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
 
import androidx.preference.PreferenceManager
 

import com.valoser.futaburakari.worker.ThreadMonitorWorker
import dagger.hilt.android.AndroidEntryPoint
import com.valoser.futaburakari.ui.detail.DetailScreenScaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.valoser.futaburakari.search.RecentSearchStore

/**
 * スレッドの詳細を Compose UI で表示する画面。
 *
 * 役割:
 * - `DetailViewModel` を用いてスレッドの詳細を読み込み、更新を監視
 * - スレッドURLごとにリストのスクロール位置を保存/復元
 * - 返信・削除・NGフィルタ更新・検索・ソーダネの操作を処理
 * - 履歴（最新レス番号とサムネイル）を更新し、スナップショット取得をトリガー
 * - ユーザー設定（テーマ/広告）や戻る操作のUXを反映
 * - 画像編集の起動（トップバーの「画像編集」アクションから `ImagePickerActivity` へ遷移）
 * - タイトル整形: カタログから渡されるタイトル文字列を 1 行表示向けに整形
 *   - HTML をプレーン化 → 改行（\n/\r）手前でカット
 *   - 改行が無い場合、半角/全角スペースの連続（3 つ以上）で手前を採用
 *   - さらに切れない場合、「スレ」という語で手前を採用（例: "◯◯スレ……" → "◯◯スレ"）
 *   - 整形後のタイトルは TopBar と履歴記録の双方に使用
 *   - 例: 「キルヒアイスレ<br>生き残って…」→ TopBar では「キルヒアイスレ」
 */
@AndroidEntryPoint
class DetailActivity : BaseActivity() {

    private val viewModel: DetailViewModel by viewModels()
    private lateinit var scrollStore: ScrollPositionStore

    private var currentUrl: String? = null

    private var isRequestingMore = false   // 追加：多重呼び出し防止
    private var isInitialLoad = true // 初期ロード復元の制御に使用（再読み込み時にリセット）

    // メインスレッドハンドラ / 既読更新デバウンス
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var markViewedRunnable: Runnable? = null
    private var markViewedJob: kotlinx.coroutines.Job? = null

    // 本文検索のためのプレーンテキストキャッシュ（Text.id -> plainText）
    private var plainTextCache: Map<String, String> = emptyMap()
    private var buildPlainCacheJob: kotlinx.coroutines.Job? = null

    private val ngStore by lazy { NgStore(this) }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private var suppressNextRestore: Boolean = false // 次回のスクロール復元を抑制するフラグ（使用中）

    private lateinit var prefs: SharedPreferences
    private val adsEnabledFlowInternal = MutableStateFlow(false)
    private val adsEnabledFlow = adsEnabledFlowInternal.asStateFlow()
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // 広告設定の即時反映
            "pref_key_ads_enabled" -> {
                setupAdBanner()
                val enabled = prefs.getBoolean("pref_key_ads_enabled", false)
                adsEnabledFlowInternal.value = enabled
            }
            // NGルール変更を即時反映（NgStore は DefaultSharedPreferences を使用）
            "ng_rules_json" -> viewModel.reapplyNgFilter()
        }
    }

    private val replyActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "送信しました。更新します。", Toast.LENGTH_SHORT).show()
            reloadDetails()
        }
    }
    // NG管理画面から戻ったらフィルタを再適用
    private val ngManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.reapplyNgFilter()
    }

    private var toolbarTitleText: String = ""
    private val bottomOffsetFlowInternal = MutableStateFlow(0)
    private val bottomOffsetFlow = bottomOffsetFlowInternal.asStateFlow()
    private val searchBarActiveFlowInternal = MutableStateFlow(false)
    private val searchBarActiveFlow = searchBarActiveFlowInternal.asStateFlow()
    private val recentSearchStore by lazy { RecentSearchStore(this) }

    /**
     * テーマ適用済みの Compose コンテンツを初期化し、UIの各種コールバックを ViewModel/ストアへ接続する。
     * 併せて履歴の記録、スナップショットの起動、戻る操作のハンドリングを設定する。
     * TopAppBar の主なアクション: 戻る/返信/再読み込み/検索/NG 管理/メディア一覧/画像編集。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compose のトップバーおよび履歴表示用のタイトル（HTML改行 <br> 等も考慮し、改行以降を除去して1行に）
        toolbarTitleText = (intent.getStringExtra(EXTRA_TITLE) ?: "").let { raw ->
            val plain = Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString()
                .replace("\u200B", "") // ZWSP 除去
            // 1) 改行優先
            val cutByNewline = plain.substringBefore('\n').substringBefore('\r').trim()
            if (cutByNewline.isNotBlank() && cutByNewline.length < plain.length) return@let cutByNewline
            // 2) 改行が無い場合のヒューリスティック: 連続する空白（半角/全角）3つ以上で区切る
            val m = Regex("[\\s\u3000]{3,}").find(plain)
            if (m != null && m.range.first > 0) return@let plain.substring(0, m.range.first).trim()
            // 3) スレ名ヒューリスティック: 「スレ」で切る（例: "キルヒアイスレ生き残…" → "キルヒアイスレ"）
            val idxSre = plain.indexOf("スレ")
            if (idxSre in 1 until plain.length) return@let plain.substring(0, idxSre + 2).trim()
            plain.trim()
        }
        currentUrl = intent.getStringExtra(EXTRA_URL)
        // スクロール位置の保存/復元に用いるストアを先に初期化（Compose へ初期状態を渡す）
        scrollStore = ScrollPositionStore(this)
        val initialScroll: Pair<Int, Int> = currentUrl?.let { url ->
            val key = UrlNormalizer.threadKey(url)
            scrollStore.getScrollState(key)
        } ?: (0 to 0)
        // Compose のコンテナへ切替（トップバー含む）。
        // メモ: カラーモード設定の個別制御は廃止（テーマに準拠）
        val showAdsPref = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("pref_key_ads_enabled", false)
        adsEnabledFlowInternal.value = showAdsPref
        val adUnitId = getString(R.string.admob_banner_id)
        setContent {
            FutaburakariTheme(expressive = true) {
                val showAds by adsEnabledFlow.collectAsState()
                DetailScreenScaffold(
                    title = toolbarTitleText,
                    onBack = {
                        onBackPressedDispatcher.onBackPressed()
                    },
                    onReply = { launchReplyActivity("") },
                    onReload = { reloadDetails() },
                    onOpenNg = { openNgManager() },
                    onOpenMedia = { },
                    // 画像編集: 端末の画像を選んで `ImageEditActivity` へ渡すフローの起点
                    onImageEdit = { startActivity(Intent(this@DetailActivity, ImagePickerActivity::class.java)) },
                    onSodaneClick = { resNum -> viewModel.postSodaNe(resNum) },
                    onDeletePost = { resNum, onlyImage ->
                        val threadUrl = currentUrl ?: return@DetailScreenScaffold
                        val boardBasePath = threadUrl.substringBeforeLast("/").substringBeforeLast("/") + "/"
                        val postUrl = boardBasePath + "futaba.php?guid=on"
                        val pwd = AppPreferences.getPwd(this)
                        viewModel.deletePost(
                            postUrl = postUrl,
                            referer = threadUrl,
                            resNum = resNum,
                            pwd = pwd ?: "",
                            onlyImage = onlyImage,
                        )
                    },
                    onSubmitSearch = { q ->
                        recentSearchStore.add(q)
                        viewModel.performSearch(q)
                    },
                    onDebouncedSearch = { q -> viewModel.performSearch(q) },
                    onClearSearch = { viewModel.clearSearch() },
                    onReapplyNgFilter = { viewModel.reapplyNgFilter() },
                    searchStateFlow = viewModel.searchState,
                    onSearchPrev = { viewModel.navigateToPrevHit() },
                    onSearchNext = { viewModel.navigateToNextHit() },
                    bottomOffsetPxFlow = bottomOffsetFlow,
                    searchActiveFlow = searchBarActiveFlow,
                    onSearchActiveChange = { active -> searchBarActiveFlowInternal.value = active },
                    recentSearchesFlow = recentSearchStore.items,
                    showAds = showAds,
                    adUnitId = adUnitId,
                    onBottomPaddingChange = { h -> bottomOffsetFlowInternal.value = h },
                    // Compose list scroll state persistence
                    initialScrollIndex = initialScroll.first,
                    initialScrollOffset = initialScroll.second,
                    onSaveScroll = { pos, off ->
                        val url = currentUrl ?: return@DetailScreenScaffold
                        val key = UrlNormalizer.threadKey(url)
                        scrollStore.saveScrollState(key, pos, off)
                    },
                    itemsFlow = viewModel.detailContent,
                    currentQueryFlow = viewModel.currentQuery,
                    getSodaneState = { rn -> viewModel.getSodaNeState(rn) },
                    // Compose側で引用一覧を表示するため、ここでは何もしない
                    onQuoteClick = null,
                    onResNumClick = { _, resBody ->
                        if (resBody.isNotEmpty()) launchReplyActivity(resBody)
                    },
                    onResNumConfirmClick = { _ -> },
                    onResNumDelClick = { resNum ->
                        // keep same as adapter behavior
                        val url = currentUrl ?: return@DetailScreenScaffold
                        val threadId = url.substringAfterLast("/").substringBefore(".htm")
                        val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
                        val postUrl = boardBasePath + "futaba.php?guid=on"
                        val pwd = AppPreferences.getPwd(this)
                        viewModel.deletePost(postUrl, url, resNum, pwd ?: "", onlyImage = false)
                    },
                    onBodyClick = { quotedBody -> launchReplyActivity(quotedBody) },
                    // Compose側でNG追加ダイアログを表示するため、ここでは何もしない
                    onAddNgFromBody = { _ -> },
                    onThreadEndTimeClick = { reloadDetails() },
                    onImageLoaded = {
                        // no-op; Compose handles scrolling alignment
                    },
                    isRefreshingFlow = viewModel.isLoading,
                    onVisibleMaxOrdinal = { ord -> markViewedByOrdinal(ord) },
                    sodaneUpdates = viewModel.sodaneUpdate,
                    threadUrl = currentUrl,
                    onNearListEnd = {
                        val url = currentUrl ?: return@DetailScreenScaffold
                        if (isRequestingMore) return@DetailScreenScaffold
                        // Compose側でファストスクロール中は抑制済み
                        isRequestingMore = true
                        suppressNextRestore = true
                        val postCount = countPostItems()
                        viewModel.checkForUpdates(url, postCount) { _ ->
                            isRequestingMore = false
                        }
                    }
                )
            }
        }

        // Hilt により viewModel は注入済み（by viewModels()）
        // SharedPreferences 準備（設定変更のリッスンに使用）
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // 履歴に記録（タイトルがない場合はURL末尾などで代替も可）
        currentUrl?.let { url ->
            val title = toolbarTitleText.ifBlank { url }
            HistoryManager.addOrUpdate(this, url, title)
            // すぐ閉じた場合でも本文を含めてローカルに残せるよう、単発のスナップショット取得を即時キュー
            ThreadMonitorWorker.snapshotNow(this, url)
            // 以降の更新を自動監視（常時有効）
            ThreadMonitorWorker.schedule(this, url)
        }

        observeViewModel()
        currentUrl?.let { viewModel.fetchDetails(it) }

        // 端末戻る：検索展開中は閉じる
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Compose検索バーが開いていれば先に閉じる
                if (searchBarActiveFlowInternal.value) {
                    searchBarActiveFlowInternal.value = false
                    return
                }
                // デフォルトの戻るへ委譲
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        // 旧来のUIリスナは削除済み（リフレッシュ/インセット処理はCompose側で対応）
    }

    /**
     * Compose 側が監視する広告表示状態を更新（バナーの表示/非表示を切り替え）。
     */
    private fun setupAdBanner() {
        // Compose側で表示を制御するため、状態のみ更新
        val enabled = prefs.getBoolean("pref_key_ads_enabled", false)
        adsEnabledFlowInternal.value = enabled
    }

    /**
     * 設定変更の監視を開始し、広告表示状態を最新に更新する。
     */
    override fun onStart() {
        super.onStart()
        // 設定変更（広告ON/OFF）を戻り時にも反映
        // レガシーUI使用時のみバナー制御（Compose移行時はCompose側で表示）
        setupAdBanner()
        // 設定変更の監視を開始
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        // Compose 側で初期スクロールを受け取るため、ここでの復元は不要
        isInitialLoad = true
    }

    /**
     * 設定変更の監視を停止する。
     */
    override fun onStop() {
        // 設定変更の監視を停止
        if (this::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        super.onStop()
    }

    // Recycler の下部余白ヘルパーは Compose 移行により不要

    // RecyclerView 経路はCompose移行に伴い削除

    

    // -------------------------
    // Flow監視
    // -------------------------
    /**
     * ViewModel の Flow/LiveData を収集し、副作用を適用する:
     * - 履歴（未読数とサムネイル）の更新
     * - 検索用プレーンテキストキャッシュのバックグラウンド構築
     * - スクロール復元用の一時フラグのリセット
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.detailContent.collect { list ->

            // 履歴の未読数更新用に最新投稿番号（Text件数）を反映
            runCatching {
                val latestReplyNo = list.count { it is DetailContent.Text }
                val threadUrl = currentUrl
                if (latestReplyNo > 0 && !threadUrl.isNullOrBlank()) {
                    HistoryManager.applyFetchResult(this@DetailActivity, threadUrl, latestReplyNo)
                }
            }

            // 履歴のサムネイル更新（最初のメディアを採用）
            runCatching {
                val media = list.firstOrNull {
                    it is DetailContent.Image || it is DetailContent.Video
                }
                val url = when (media) {
                    is DetailContent.Image -> media.imageUrl
                    is DetailContent.Video -> media.videoUrl
                    else -> null
                }
                val threadUrl = currentUrl
                if (!url.isNullOrBlank() && !threadUrl.isNullOrBlank()) {
                    HistoryManager.updateThumbnail(this@DetailActivity, threadUrl, url)
                }
            }

            // Compose側は LiveData を直接購読しているため、ここでのAdapter更新は不要

            // プレーンテキストキャッシュをバックグラウンドで構築
            buildPlainCacheJob?.cancel()
            buildPlainCacheJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val cache = list.asSequence()
                    .filterIsInstance<DetailContent.Text>()
                    .associate { t ->
                        val plain = android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT)
                            .toString()
                        t.id to plain
                    }
                withContext(kotlinx.coroutines.Dispatchers.Main) { plainTextCache = cache }
            }

            // 検索ナビの表示は ViewModel.searchState に統一

            // suppressNextRestoreフラグのリセットのみ残す
            if (suppressNextRestore) {
                suppressNextRestore = false
            }
                }
            }
        }

        viewModel.error.observe(this, Observer { err ->
            err?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        })

        // Compose リストはUI内で楽観更新のため、Adapter反映やProgressBarは不要

    }

    /**
     * Forces reloading of details while preserving Compose-managed scroll state.
     */
    private fun reloadDetails() {
        currentUrl?.let { url ->
            suppressNextRestore = false
            // Compose側でスクロールは保持・保存されるため明示の保存/復元は不要
            viewModel.clearSearch()
            isInitialLoad = true // ★リロード時は再度復元を許可する
            viewModel.fetchDetails(url, forceRefresh = true)
        }
    }

    // AdMob のライフサイクル制御は Compose 側で不要

    // スクロール保存/復元は Compose 側の onSaveScroll と initialScrollIndex/Offset で処理

    // メニューはCompose TopBarで提供するため未使用

    // ViewBindingは撤去済み

    // ★ 変更点 4: 返信画面を起動する共通メソッド
    /**
     * Launches the reply UI for the current thread with an optional quoted body.
     */
    private fun launchReplyActivity(quote: String) {
        currentUrl?.let { url ->
            val threadId = url.substringAfterLast("/").substringBefore(".htm")
            val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
            val boardPostUrl = boardBasePath + "futaba.php"
            val intent = Intent(this, ReplyActivity::class.java).apply {
                putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                putExtra(ReplyActivity.EXTRA_THREAD_TITLE, toolbarTitleText)
                putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, quote)
            }
            replyActivityResultLauncher.launch(intent)
        }
    }

    /** NG管理画面を開く。詳細画面からの起動時はスレタイNGを非表示にする。 */
    private fun openNgManager() {
        ngManagerLauncher.launch(
            Intent(this, NgManagerActivity::class.java).apply {
                // DetailActivityからの起動時はスレタイNGは不要
                putExtra(NgManagerActivity.EXTRA_HIDE_TITLE, true)
            }
        )
    }

    // showAddNgDialog はComposeに移行済みのため削除しました

    // =========================================================
    // ここから：ポップアップ表示（引用 / ID）と検索ヘルパー
    // =========================================================

    // 引用ポップアップ：> / >> / >>> など多段対応（複数候補にも対応）
    // showQuotePopup/showIdPostsPopup はComposeへ移行済み

    // BottomSheet に DetailAdapter で並べる（遷移は無効化）
    // 旧Viewベースのシート表示やメディア一覧はComposeへ移行済み

    // Compose リスト用：可視最大序数が通知されたら既読を更新（デバウンスあり）
    /**
     * 画面上で可視となった最大序数に基づき、最終既読レス番号をデバウンス更新する。
     */
    private fun markViewedByOrdinal(maxOrdinal: Int) {
        if (maxOrdinal <= 0) return
        markViewedRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            markViewedJob?.cancel()
            markViewedJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val url = currentUrl ?: return@launch
                val current = HistoryManager.getAll(this@DetailActivity).firstOrNull { it.url == url }
                val curViewed = current?.lastViewedReplyNo ?: 0
                if (maxOrdinal > curViewed) {
                    HistoryManager.markViewed(this@DetailActivity, url, maxOrdinal)
                }
            }
        }
        markViewedRunnable = r
        mainHandler.postDelayed(r, 300L)
    }

    // 既読更新（Compose版）は onVisibleMaxOrdinal -> markViewedByOrdinal に統一

    // 「No.xxx」「ファイル名」「本文一部」いずれかで対象を検索
    /**
     * クエリ文字列に基づき対象コンテンツを検索する。
     * サポート:
     * 1) 本文中の "No.<番号>" マッチ
     * 2) 画像/動画のファイル名またはURL末尾の一致
     * 3) 本文プレーンテキストの部分一致（大文字小文字無視）
     */
    private fun findContentByText(all: List<DetailContent>, searchText: String): DetailContent? {
        // 1) No.\d+
        Regex("""No\.(\d+)""").find(searchText)?.groupValues?.getOrNull(1)?.let { num ->
            val hit = all.firstOrNull {
                it is DetailContent.Text && (plainTextCache[it.id]?.contains("No.$num") == true)
            }
            if (hit != null) return hit
        }

        // 2) 画像/動画 ファイル名末尾一致
        for (c in all) {
            when (c) {
                is DetailContent.Image -> if (c.fileName == searchText || c.imageUrl.endsWith(searchText)) return c
                is DetailContent.Video -> if (c.fileName == searchText || c.videoUrl.endsWith(searchText)) return c
                else -> {}
            }
        }

        // 3) 本文 部分一致（空白圧縮・大文字小文字無視）
        val needle = searchText.trim().replace(Regex("\\s+"), " ")
        return all.firstOrNull {
            it is DetailContent.Text && (plainTextCache[it.id]
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.contains(needle, ignoreCase = true) == true)
        }
    }

    // 行頭が '>' 1個の引用行（最初の1つ）を返す（旧：単一版）
    /** 行頭が '>' 1個の引用行（最初の1つ）を抽出する。 */
    private fun extractFirstLevelQuoteCore(item: DetailContent.Text): String? {
        val plain = Html.fromHtml(item.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
        val m = Regex("^>([^>].+)$", RegexOption.MULTILINE).find(plain)
        return m?.groupValues?.getOrNull(1)?.trim()
    }

    // 行頭が '>' 1個の引用行を「複数」返す（多段で複数候補がある場合に使用）
    /** 行頭が '>' 1個の引用行（複数）をすべて抽出する。 */
    private fun extractFirstLevelQuoteCores(item: DetailContent.Text): List<String> {
        val plain = Html.fromHtml(item.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
        return Regex("^>([^>].+)$", RegexOption.MULTILINE)
            .findAll(plain)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun showToastOnUiThread(message: String, duration: Int) {
        runOnUiThread { Toast.makeText(this, message, duration).show() }
    }

    // 削除確認ダイアログはCompose側に統一

    // RecyclerView末尾探索は不要

    // ThreadEndTime の表示正規化は Compose 側に統一

    // レス数（Text/Image/Video の件数）を返す
    private fun countPostItems(): Int {
        val list = viewModel.detailContent.value
        return list.count { it is DetailContent.Text || it is DetailContent.Image || it is DetailContent.Video }
    }

    // 参照一覧のUIはComposeに統一（Activity側UIなし）

    // 追加: 共通のマッチ関数
    // 引用参照の一致判定はCompose側で実施（ここでは未使用）

}
