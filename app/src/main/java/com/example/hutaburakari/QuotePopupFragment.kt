// QuotePopupFragment.kt

package com.example.hutaburakari

import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.hutaburakari.databinding.FragmentQuotePopupBinding // レイアウトファイル名に合わせてください
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class QuotePopupFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentQuotePopupBinding? = null
    private val binding get() = _binding!!

    // ActivityとViewModelを共有
    private val viewModel: DetailViewModel by activityViewModels()
    private lateinit var detailAdapter: DetailAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // レイアウトファイル名は 'fragment_quote_popup.xml' を想定しています
        _binding = FragmentQuotePopupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()

        val mode = requireArguments().getString(ARG_MODE)
        val title = requireArguments().getString(ARG_TITLE) ?: ""
        binding.popupTitle.text = title
        binding.closeButton.setOnClickListener { dismiss() }

        val allContent = viewModel.detailContent.value.orEmpty()

        val items: List<DetailContent> = when (mode) {
            MODE_QUOTE -> {
                val text = requireArguments().getString(ARG_QUOTED_TEXT).orEmpty()
                val level = requireArguments().getInt(ARG_QUOTE_LEVEL, 1)
                resolveQuotedContent(allContent, text, level)
            }
            MODE_ID -> {
                val id = requireArguments().getString(ARG_ID).orEmpty()
                resolveIdContent(allContent, id)
            }
            else -> emptyList()
        }
        detailAdapter.submitList(null)
        detailAdapter.submitList(items)
    }

    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter().apply {
            // ポップアップ内では、さらなるポップアップ表示は行わない
            onQuoteClickListener = null
            onIdClickListener = null
            onSodaNeClickListener = null
            onResNumClickListener = null
        }
        binding.popupRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.popupRecyclerView.adapter = detailAdapter

        // ★ 追加（Activity と同等の安定表示設定）
        binding.popupRecyclerView.setHasFixedSize(true)
        binding.popupRecyclerView.itemAnimator = null
        binding.popupRecyclerView.setItemViewCacheSize(100)

        // ここで適用
        binding.popupRecyclerView.addItemDecoration(
            BlockDividerDecoration(detailAdapter, requireContext(), paddingStartDp = 0, paddingEndDp = 0)
        )

    }

    // --- データ解決ロジック ---
    private fun resolveQuotedContent(
        all: List<DetailContent>,
        quotedText: String,
        level: Int
    ): List<DetailContent> {
        val needle = quotedText.trim().replace(Regex("\\s+"), " ")
        val textIndexes = all.withIndex().filter { (_, c) ->
            c is DetailContent.Text &&
                    Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT)
                        .toString()
                        .replace(Regex("\\s+"), " ")
                        .contains(needle, ignoreCase = true)
        }.map { it.index }

        return collectWithTrailingMedia(all, textIndexes)
    }

    private fun resolveIdContent(all: List<DetailContent>, id: String): List<DetailContent> {
        val key = "ID:$id"
        val textIndexes = all.withIndex().filter { (_, c) ->
            c is DetailContent.Text &&
                    Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().contains(key)
        }.map { it.index }
        return collectWithTrailingMedia(all, textIndexes)
    }

    private fun findContentByText(all: List<DetailContent>, searchText: String): DetailContent? {
        // 1) レス番号 (No.123)
        Regex("""No\.(\d+)""").find(searchText)?.groupValues?.getOrNull(1)?.let { num ->
            val hit = all.firstOrNull {
                it is DetailContent.Text && Html.fromHtml(it.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().contains("No.$num")
            }
            if (hit != null) return hit
        }
        // 2) ファイル名
        all.firstOrNull { c ->
            (c is DetailContent.Image && (c.fileName == searchText || c.imageUrl.endsWith(searchText))) ||
                    (c is DetailContent.Video && (c.fileName == searchText || c.videoUrl.endsWith(searchText)))
        }?.let { return it }

        // 3) 本文
        val needle = searchText.trim().replace(Regex("\\s+"), " ")
        return all.firstOrNull {
            it is DetailContent.Text && Html.fromHtml(it.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                .replace(Regex("\\s+"), " ").contains(needle, ignoreCase = true)
        }
    }

    private fun extractFirstLevelQuoteCores(item: DetailContent): List<String> {
        val text = (item as? DetailContent.Text)?.let {
            Html.fromHtml(it.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
        } ?: return emptyList()

        // 行頭が '>' 1個で始まる行のみ（'>>' 以上はここでは除外）
        return Regex("^>([^>].+)$", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // 直後の画像/動画を「次の Text/ThreadEndTime まで」同梱して返す（登場順を維持）
    private fun collectWithTrailingMedia(
        all: List<DetailContent>,
        textIndexes: List<Int>
    ): List<DetailContent> {
        val paired = mutableListOf<Pair<Int, DetailContent>>() // (全体インデックス, アイテム)

        for (i in textIndexes) {
            if (i !in all.indices) continue
            // 本文
            paired += i to all[i]
            // 直後メディア（次の Text/ThreadEndTime まで）
            var j = i + 1
            while (j < all.size) {
                when (val c = all[j]) {
                    is DetailContent.Image, is DetailContent.Video -> { paired += j to c; j++ }
                    is DetailContent.Text, is DetailContent.ThreadEndTime -> break
                }
            }
        }

        // ❌ 削除：paired.sortBy { it.first }  ← これが分離の元凶になりやすい
        // 収集した順（= スレ内登場順）のまま重複だけ落とす
        val seen = HashSet<String>()
        val out = ArrayList<DetailContent>(paired.size)
        for ((_, item) in paired) if (seen.add(item.id)) out += item
        return out
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_TITLE = "title"
        private const val ARG_QUOTED_TEXT = "quoted_text"
        private const val ARG_QUOTE_LEVEL = "quote_level"
        private const val ARG_ID = "id"

        private const val MODE_QUOTE = "quote"
        private const val MODE_ID = "id"

        fun showForQuote(fm: FragmentManager, quotedText: String, quoteLevel: Int) {
            newInstance(MODE_QUOTE, if (quoteLevel > 1) "引用元 (>>)" else "引用元 (>)", quotedText, quoteLevel, null)
                .show(fm, "quote_popup")
        }

        fun showForId(fm: FragmentManager, id: String) {
            newInstance(MODE_ID, "ID: $id の投稿", null, 0, id)
                .show(fm, "id_popup")
        }

        private fun newInstance(mode: String, title: String, quotedText: String?, quoteLevel: Int, id: String?) =
            QuotePopupFragment().apply {
                arguments = bundleOf(
                    ARG_MODE to mode,
                    ARG_TITLE to title,
                    ARG_QUOTED_TEXT to quotedText,
                    ARG_QUOTE_LEVEL to quoteLevel,
                    ARG_ID to id
                )
            }
    }
}