package com.valoser.futaburakari

/**
 * Centralised catalogue of preset bookmarks used for onboarding and quick additions.
 *
 * The list mirrors Futaba's public board directory so the preset picker can surface every board
 * without requiring manual URL entry. Each entry carries a lightweight category tag that the UI
 * chips use for filtering.
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

    private data class BoardDefinition(
        val name: String,
        val basePath: String,
        val category: String,
    )

    private val boardDefinitions = listOf(
        BoardDefinition("二次元裏 (may)", "https://may.2chan.net/b/", "人気"),
        BoardDefinition("二次元裏 (dec)", "https://dec.2chan.net/dec/", "人気"),
        BoardDefinition("二次元裏 (jun)", "https://jun.2chan.net/jun/", "人気"),
        BoardDefinition("雑談", "https://img.2chan.net/9/", "人気"),
        BoardDefinition("避難所", "https://www.2chan.net/hinan/", "人気"),
        BoardDefinition("webm", "https://may.2chan.net/webm/", "人気"),
        BoardDefinition("転載不可", "https://dec.2chan.net/58/", "人気"),
        BoardDefinition("転載可", "https://dec.2chan.net/59/", "人気"),
        BoardDefinition("そうだね", "https://dec.2chan.net/71/", "人気"),
        BoardDefinition("任天堂", "https://dec.2chan.net/82/", "人気"),
        BoardDefinition("ソニー", "https://dec.2chan.net/61/", "人気"),
        BoardDefinition("VTuber", "https://dec.2chan.net/73/", "アニメ・ゲーム"),
        BoardDefinition("ホロライブ", "https://dec.2chan.net/84/", "アニメ・ゲーム"),
        BoardDefinition("合成音声", "https://dec.2chan.net/81/", "アニメ・ゲーム"),
        BoardDefinition("人工知能", "https://dec.2chan.net/85/", "技術"),
        BoardDefinition("二次元", "https://dat.2chan.net/img2/", "アニメ・ゲーム"),
        BoardDefinition("二次元ID", "https://may.2chan.net/id/", "アニメ・ゲーム"),
        BoardDefinition("二次元ネタ", "https://dat.2chan.net/16/", "アニメ・ゲーム"),
        BoardDefinition("二次元業界", "https://dat.2chan.net/43/", "アニメ・ゲーム"),
        BoardDefinition("二次元グロ", "https://cgi.2chan.net/o/", "アニメ・ゲーム"),
        BoardDefinition("二次元グロ裏", "https://jun.2chan.net/51/", "アニメ・ゲーム"),
        BoardDefinition("FGO", "https://dec.2chan.net/74/", "アニメ・ゲーム"),
        BoardDefinition("アイマス", "https://dec.2chan.net/75/", "アニメ・ゲーム"),
        BoardDefinition("ZOIDS", "https://dec.2chan.net/86/", "アニメ・ゲーム"),
        BoardDefinition("ウメハラ総合", "https://dec.2chan.net/78/", "アニメ・ゲーム"),
        BoardDefinition("ゲーム", "https://jun.2chan.net/31/", "アニメ・ゲーム"),
        BoardDefinition("ネトゲ", "https://nov.2chan.net/28/", "アニメ・ゲーム"),
        BoardDefinition("ソシャゲ", "https://dec.2chan.net/56/", "アニメ・ゲーム"),
        BoardDefinition("艦これ", "https://dec.2chan.net/60/", "アニメ・ゲーム"),
        BoardDefinition("刀剣乱舞", "https://dec.2chan.net/65/", "アニメ・ゲーム"),
        BoardDefinition("東方", "https://may.2chan.net/40/", "アニメ・ゲーム"),
        BoardDefinition("東方裏", "https://dec.2chan.net/55/", "アニメ・ゲーム"),
        BoardDefinition("えろげ", "https://zip.2chan.net/5/", "アニメ・ゲーム"),
        BoardDefinition("麻雀", "https://may.2chan.net/25/", "趣味"),
        BoardDefinition("うま", "https://may.2chan.net/26/", "スポーツ"),
        BoardDefinition("ねこ", "https://may.2chan.net/27/", "趣味"),
        BoardDefinition("どうぶつ", "https://dat.2chan.net/d/", "趣味"),
        BoardDefinition("しょくぶつ", "https://zip.2chan.net/z/", "趣味"),
        BoardDefinition("虫", "https://dat.2chan.net/w/", "趣味"),
        BoardDefinition("アクア", "https://dat.2chan.net/49/", "趣味"),
        BoardDefinition("アウトドア", "https://dec.2chan.net/62/", "趣味"),
        BoardDefinition("のりもの", "https://dat.2chan.net/e/", "趣味"),
        BoardDefinition("二輪", "https://dat.2chan.net/j/", "趣味"),
        BoardDefinition("自転車", "https://nov.2chan.net/37/", "スポーツ"),
        BoardDefinition("カメラ", "https://dat.2chan.net/45/", "趣味"),
        BoardDefinition("家電", "https://dat.2chan.net/48/", "生活"),
        BoardDefinition("鉄道", "https://dat.2chan.net/r/", "趣味"),
        BoardDefinition("模型", "https://dat.2chan.net/v/", "趣味"),
        BoardDefinition("模型裏", "https://nov.2chan.net/y/", "趣味"),
        BoardDefinition("模型裏 (jun)", "https://jun.2chan.net/47/", "趣味"),
        BoardDefinition("自作PC", "https://zip.2chan.net/3/", "技術"),
        BoardDefinition("料理", "https://dat.2chan.net/t/", "生活"),
        BoardDefinition("甘味", "https://dat.2chan.net/20/", "生活"),
        BoardDefinition("ラーメン", "https://dat.2chan.net/21/", "生活"),
        BoardDefinition("ファッション", "https://dec.2chan.net/66/", "生活"),
        BoardDefinition("旅行", "https://dec.2chan.net/67/", "生活"),
        BoardDefinition("子育て", "https://dec.2chan.net/68/", "生活"),
        BoardDefinition("占い", "https://dec.2chan.net/64/", "生活"),
        BoardDefinition("野球", "https://zip.2chan.net/1/", "スポーツ"),
        BoardDefinition("サッカー", "https://zip.2chan.net/12/", "スポーツ"),
        BoardDefinition("三次実況", "https://dec.2chan.net/50/", "カルチャー"),
        BoardDefinition("モアイ", "https://dec.2chan.net/69/", "カルチャー"),
        BoardDefinition("ネットキャラ", "https://dat.2chan.net/10/", "カルチャー"),
        BoardDefinition("なりきり", "https://nov.2chan.net/34/", "カルチャー"),
        BoardDefinition("自作絵", "https://zip.2chan.net/11/", "創作"),
        BoardDefinition("自作絵裏", "https://zip.2chan.net/14/", "創作"),
        BoardDefinition("女装", "https://zip.2chan.net/32/", "カルチャー"),
        BoardDefinition("ばら", "https://zip.2chan.net/15/", "カルチャー"),
        BoardDefinition("ゆり", "https://zip.2chan.net/7/", "カルチャー"),
        BoardDefinition("やおい", "https://zip.2chan.net/8/", "カルチャー"),
        BoardDefinition("特撮", "https://cgi.2chan.net/g/", "カルチャー"),
        BoardDefinition("ろぼ", "https://zip.2chan.net/2/", "カルチャー"),
        BoardDefinition("映画", "https://dec.2chan.net/63/", "カルチャー"),
        BoardDefinition("おもちゃ", "https://dat.2chan.net/44/", "カルチャー"),
        BoardDefinition("スピグラ", "https://dat.2chan.net/23/", "カルチャー"),
        BoardDefinition("お絵かき", "https://zip.2chan.net/p/", "創作"),
        BoardDefinition("落書き", "https://nov.2chan.net/q/", "創作"),
        BoardDefinition("落書き裏", "https://cgi.2chan.net/u/", "創作"),
        BoardDefinition("3DCG", "https://dat.2chan.net/x/", "創作"),
        BoardDefinition("数学", "https://cgi.2chan.net/m/", "技術"),
        BoardDefinition("flash", "https://cgi.2chan.net/i/", "技術"),
        BoardDefinition("壁紙", "https://cgi.2chan.net/k/", "創作"),
        BoardDefinition("壁紙二", "https://dat.2chan.net/l/", "創作"),
        BoardDefinition("レイアウト", "https://may.2chan.net/layout/", "技術"),
        BoardDefinition("政治", "https://nov.2chan.net/35/", "ニュース"),
        BoardDefinition("経済", "https://nov.2chan.net/36/", "ニュース"),
        BoardDefinition("宗教", "https://dec.2chan.net/79/", "ニュース"),
        BoardDefinition("軍", "https://cgi.2chan.net/f/", "ニュース"),
        BoardDefinition("軍裏", "https://may.2chan.net/39/", "ニュース"),
        BoardDefinition("ニュース表", "https://zip.2chan.net/6/", "ニュース"),
        BoardDefinition("昭和", "https://dec.2chan.net/76/", "カルチャー"),
        BoardDefinition("平成", "https://dec.2chan.net/77/", "カルチャー"),
        BoardDefinition("発電", "https://dec.2chan.net/53/", "ニュース"),
        BoardDefinition("自然災害", "https://dec.2chan.net/52/", "ニュース"),
        BoardDefinition("コロナ", "https://dec.2chan.net/83/", "ニュース"),
        BoardDefinition("新板提案", "https://dec.2chan.net/70/", "カルチャー"),
        BoardDefinition("IPv6", "https://ipv6.2chan.net/54/", "技術"),
        BoardDefinition("あぷ", "https://dec.2chan.net/up/", "その他"),
        BoardDefinition("あぷ小", "https://dec.2chan.net/up2/", "その他"),
    )

    private val allPresets: List<Preset> = boardDefinitions.map { definition ->
        Preset(
            name = definition.name,
            url = catalogUrl(definition.basePath),
            category = definition.category
        )
    }

    private fun catalogUrl(basePath: String): String {
        val normalized = if (basePath.endsWith("/")) basePath else "$basePath/"
        return "$normalizedfutaba.php?mode=cat"
    }

    /** Returns all preset categories sorted alphabetically with "すべて" leading. */
    fun categories(): List<String> {
        val set = linkedSetOf("すべて")
        allPresets.mapTo(set) { it.category }
        return set.toList()
    }

    /** Returns every preset in the catalogue. */
    fun all(): List<Preset> = allPresets

    private val defaultSeedNames = listOf("二次元裏 (may)", "雑談", "どうぶつ")

    /** Presets used when seeding a brand-new installation. */
    fun defaultSeeds(): List<Preset> = defaultSeedNames.mapNotNull { name ->
        allPresets.find { it.name == name }
    }

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
