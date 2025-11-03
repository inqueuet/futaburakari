package com.valoser.futaburakari

/**
 * Centralised catalogue of preset bookmarks used for onboarding and quick additions.
 *
 * Each preset carries a human readable category so UI surfaces can group or filter entries
 * without hard-coding label rules in multiple places.
 */
object BookmarkPresets {

    /** Describes a single preset entry. */
    data class Preset(
        val name: String,
        val url: String,
        val category: String,
    ) {
        fun toBookmark(): Bookmark = Bookmark(name = name, url = url)
        val baseUrl: String = url.substringBefore("?")
    }

    /**
     * Preset pool curated from popular Futaba boards. Categories are lightweight groupings for UI
     * filtering; they do not affect behaviour.
     */
    private val allPresets: List<Preset> = listOf(
        Preset("二次元裏 (may)", "https://may.2chan.net/b/futaba.php?mode=cat", "人気"),
        Preset("二次元裏 (dec)", "https://dec.2chan.net/dec/futaba.php?mode=cat", "人気"),
        Preset("雑談", "https://img.2chan.net/9/futaba.php?mode=cat", "人気"),
        Preset("避難所", "https://www.2chan.net/hinan/futaba.php?mode=cat", "人気"),
        Preset("webm", "https://may.2chan.net/webm/futaba.php?mode=cat", "人気"),
        Preset("どうぶつ", "https://dat.2chan.net/d/futaba.php?mode=cat", "趣味"),
        Preset("しょくぶつ", "https://zip.2chan.net/z/futaba.php?mode=cat", "趣味"),
        Preset("ねこ", "https://may.2chan.net/27/futaba.php?mode=cat", "趣味"),
        Preset("虫", "https://dat.2chan.net/w/futaba.php?mode=cat", "趣味"),
        Preset("カメラ", "https://dat.2chan.net/45/futaba.php?mode=cat", "趣味"),
        Preset("鉄道", "https://dat.2chan.net/r/futaba.php?mode=cat", "趣味"),
        Preset("アウトドア", "https://dec.2chan.net/62/futaba.php?mode=cat", "趣味"),
        Preset("旅行", "https://dec.2chan.net/67/futaba.php?mode=cat", "生活"),
        Preset("料理", "https://dat.2chan.net/t/futaba.php?mode=cat", "生活"),
        Preset("甘味", "https://dat.2chan.net/20/futaba.php?mode=cat", "生活"),
        Preset("ラーメン", "https://dat.2chan.net/21/futaba.php?mode=cat", "生活"),
        Preset("ファッション", "https://dec.2chan.net/66/futaba.php?mode=cat", "生活"),
        Preset("家電", "https://dat.2chan.net/48/futaba.php?mode=cat", "生活"),
        Preset("二次元", "https://dat.2chan.net/img2/futaba.php?mode=cat", "アニメ・ゲーム"),
        Preset("ホロライブ", "https://dec.2chan.net/84/futaba.php?mode=cat", "アニメ・ゲーム"),
        Preset("ゲーム", "https://jun.2chan.net/31/futaba.php?mode=cat", "アニメ・ゲーム"),
        Preset("FGO", "https://dec.2chan.net/74/futaba.php?mode=cat", "アニメ・ゲーム"),
        Preset("アイマス", "https://dec.2chan.net/75/futaba.php?mode=cat", "アニメ・ゲーム"),
        Preset("ソシャゲ", "https://dec.2chan.net/56/futaba.php?mode=cat", "アニメ・ゲーム"),
        Preset("野球", "https://zip.2chan.net/1/futaba.php?mode=cat", "スポーツ"),
        Preset("サッカー", "https://zip.2chan.net/12/futaba.php?mode=cat", "スポーツ"),
        Preset("うま", "https://may.2chan.net/26/futaba.php?mode=cat", "スポーツ"),
        Preset("自転車", "https://nov.2chan.net/37/futaba.php?mode=cat", "スポーツ"),
        Preset("昭和", "https://dec.2chan.net/76/futaba.php?mode=cat", "カルチャー"),
        Preset("平成", "https://dec.2chan.net/77/futaba.php?mode=cat", "カルチャー"),
        Preset("新板提案", "https://dec.2chan.net/70/futaba.php?mode=cat", "カルチャー"),
        Preset("二次元ネタ", "https://dat.2chan.net/16/futaba.php?mode=cat", "カルチャー"),
        Preset("二次元業界", "https://dat.2chan.net/43/futaba.php?mode=cat", "カルチャー"),
    )

    /** Returns all preset categories sorted alphabetically with "すべて" leading. */
    fun categories(): List<String> {
        val set = linkedSetOf("すべて")
        allPresets.mapTo(set) { it.category }
        return set.toList()
    }

    /** Returns every preset in the catalogue. */
    fun all(): List<Preset> = allPresets

    /** Presets used when seeding a brand-new installation. */
    fun defaultSeeds(): List<Preset> = listOf(
        Preset("二次元裏 (may)", "https://may.2chan.net/b/futaba.php?mode=cat", "人気"),
        Preset("雑談", "https://img.2chan.net/9/futaba.php?mode=cat", "人気"),
        Preset("どうぶつ", "https://dat.2chan.net/d/futaba.php?mode=cat", "趣味"),
    )

    /**
     * Filters presets by query/category while excluding URLs already present in the provided
     * bookmark collections.
     */
    fun filteredPresets(
        query: String,
        category: String?,
        existingBookmarks: Collection<Bookmark>,
        additionalExclusions: Collection<Preset> = emptyList(),
    ): List<Preset> {
        val normalizedExisting = existingBookmarks.map { it.url.substringBefore("?") }.toSet()
        val normalizedAdditional = additionalExclusions.map { it.baseUrl }.toSet()
        val normalizedQuery = query.trim().lowercase()

        return allPresets.asSequence()
            .filter { preset -> preset.baseUrl !in normalizedExisting && preset.baseUrl !in normalizedAdditional }
            .filter { preset ->
                category.isNullOrBlank() || category == "すべて" || preset.category == category
            }
            .filter { preset ->
                if (normalizedQuery.isBlank()) {
                    true
                } else {
                    preset.name.lowercase().contains(normalizedQuery) ||
                        preset.category.lowercase().contains(normalizedQuery)
                }
            }
            .toList()
    }
}
