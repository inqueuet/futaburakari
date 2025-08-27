package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object BookmarkManager {

    private const val PREFS_NAME = "com.valoser.futaburakari.bookmarks"
    private const val KEY_BOOKMARKS = "bookmarks_list"
    private const val KEY_SELECTED_BOOKMARK_URL = "selected_bookmark_url"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveBookmarks(context: Context, bookmarks: List<Bookmark>) {
        val prefs = getPreferences(context)
        val editor = prefs.edit()
        val gson = Gson()
        val json = gson.toJson(bookmarks)
        editor.putString(KEY_BOOKMARKS, json)
        editor.apply()
    }

    fun getBookmarks(context: Context): MutableList<Bookmark> {
        val prefs = getPreferences(context)
        val gson = Gson()
        val json = prefs.getString(KEY_BOOKMARKS, null)
        val type = object : TypeToken<MutableList<Bookmark>>() {}.type
        var bookmarks: MutableList<Bookmark>? = gson.fromJson(json, type)
        // If no bookmarks exist, add the original default URL as the first bookmark.
        if (bookmarks == null || bookmarks.isEmpty()) {
            bookmarks = mutableListOf(
                Bookmark("ホロライブ", "https://dec.2chan.net/84/futaba.php?mode=cat"),
                Bookmark("避難所", "https://www.2chan.net/hinan/futaba.php?mode=cat"),
                Bookmark("野球", "https://zip.2chan.net/1/futaba.php?mode=cat"),
                Bookmark("サッカー", "https://zip.2chan.net/12/futaba.php?mode=cat"),
                Bookmark("麻雀", "https://may.2chan.net/25/futaba.php?mode=cat"),
                Bookmark("うま", "https://may.2chan.net/26/futaba.php?mode=cat"),
                Bookmark("ねこ", "https://may.2chan.net/27/futaba.php?mode=cat"),
                Bookmark("どうぶつ", "https://dat.2chan.net/d/futaba.php?mode=cat"),
                Bookmark("しょくぶつ", "https://zip.2chan.net/z/futaba.php?mode=cat"),
                Bookmark("虫", "https://dat.2chan.net/w/futaba.php?mode=cat"),
                Bookmark("アクア", "https://dat.2chan.net/49/futaba.php?mode=cat"),
                Bookmark("アウトドア", "https://dec.2chan.net/62/futaba.php?mode=cat"),
                Bookmark("料理", "https://dat.2chan.net/t/futaba.php?mode=cat"),
                Bookmark("甘味", "https://dat.2chan.net/20/futaba.php?mode=cat"),
                Bookmark("ラーメン", "https://dat.2chan.net/21/futaba.php?mode=cat"),
                Bookmark("のりもの", "https://dat.2chan.net/e/futaba.php?mode=cat"),
                Bookmark("二輪", "https://dat.2chan.net/j/futaba.php?mode=cat"),
                Bookmark("自転車", "https://nov.2chan.net/37/futaba.php?mode=cat"),
                Bookmark("カメラ", "https://dat.2chan.net/45/futaba.php?mode=cat"),
                Bookmark("家電", "https://dat.2chan.net/48/futaba.php?mode=cat"),
                Bookmark("鉄道", "https://dat.2chan.net/r/futaba.php?mode=cat"),
                Bookmark("二次元", "https://dat.2chan.net/img2/futaba.php?mode=cat"),
                Bookmark("二次元裏", "https://dec.2chan.net/dec/futaba.php?mode=cat"),
                Bookmark("二次元裏", "https://jun.2chan.net/jun/futaba.php?mode=cat"),
                Bookmark("二次元裏", "https://may.2chan.net/b/futaba.php?mode=cat"),
                Bookmark("転載不可", "https://dec.2chan.net/58/futaba.php?mode=cat"),
                Bookmark("転載可", "https://dec.2chan.net/59/futaba.php?mode=cat"),
                Bookmark("二次元ID", "https://may.2chan.net/id/futaba.php?mode=cat"),
                Bookmark("スピグラ", "https://dat.2chan.net/23/futaba.php?mode=cat"),
                Bookmark("二次元ネタ", "https://dat.2chan.net/16/futaba.php?mode=cat"),
                Bookmark("二次元業界", "https://dat.2chan.net/43/futaba.php?mode=cat"),
                Bookmark("FGO", "https://dec.2chan.net/74/futaba.php?mode=cat"),
                Bookmark("アイマス", "https://dec.2chan.net/75/futaba.php?mode=cat"),
                Bookmark("ZOIDS", "https://dec.2chan.net/86/futaba.php?mode=cat"),
                Bookmark("ウメハラ総合", "https://dec.2chan.net/78/futaba.php?mode=cat"),
                Bookmark("ゲーム", "https://jun.2chan.net/31/futaba.php?mode=cat"),
                Bookmark("ネトゲ", "https://nov.2chan.net/28/futaba.php?mode=cat"),
                Bookmark("ソシャゲ", "https://dec.2chan.net/56/futaba.php?mode=cat"),
                Bookmark("艦これ", "https://dec.2chan.net/60/futaba.php?mode=cat"),
                Bookmark("モアイ", "https://dec.2chan.net/69/futaba.php?mode=cat"),
                Bookmark("刀剣乱舞", "https://dec.2chan.net/65/futaba.php?mode=cat"),
                Bookmark("占い", "https://dec.2chan.net/64/futaba.php?mode=cat"),
                Bookmark("ファッション", "https://dec.2chan.net/66/futaba.php?mode=cat"),
                Bookmark("旅行", "https://dec.2chan.net/67/futaba.php?mode=cat"),
                Bookmark("子育て", "https://dec.2chan.net/68/futaba.php?mode=cat"),
                Bookmark("webm", "https://may.2chan.net/webm/futaba.php?mode=cat"),
                Bookmark("そうだね", "https://dec.2chan.net/71/futaba.php?mode=cat"),
                Bookmark("任天堂", "https://dec.2chan.net/82/futaba.php?mode=cat"),
                Bookmark("ソニー", "https://dec.2chan.net/61/futaba.php?mode=cat"),
                Bookmark("ネットキャラ", "https://dat.2chan.net/10/futaba.php?mode=cat"),
                Bookmark("なりきり", "https://nov.2chan.net/34/futaba.php?mode=cat"),
                Bookmark("自作絵", "https://zip.2chan.net/11/futaba.php?mode=cat"),
                Bookmark("自作絵裏", "https://zip.2chan.net/14/futaba.php?mode=cat"),
                Bookmark("女装", "https://zip.2chan.net/32/futaba.php?mode=cat"),
                Bookmark("ばら", "https://zip.2chan.net/15/futaba.php?mode=cat"),
                Bookmark("ゆり", "https://zip.2chan.net/7/futaba.php?mode=cat"),
                Bookmark("やおい", "https://zip.2chan.net/8/futaba.php?mode=cat"),
                Bookmark("自作PC", "https://zip.2chan.net/3/futaba.php?mode=cat"),
                Bookmark("特撮", "https://cgi.2chan.net/g/futaba.php?mode=cat"),
                Bookmark("ろぼ", "https://zip.2chan.net/2/futaba.php?mode=cat"),
                Bookmark("映画", "https://dec.2chan.net/63/futaba.php?mode=cat"),
                Bookmark("おもちゃ", "https://dat.2chan.net/44/futaba.php?mode=cat"),
                Bookmark("模型", "https://dat.2chan.net/v/futaba.php?mode=cat"),
                Bookmark("模型裏", "https://nov.2chan.net/y/futaba.php?mode=cat"),
                Bookmark("模型裏", "https://jun.2chan.net/47/futaba.php?mode=cat"),
                Bookmark("VTuber", "https://dec.2chan.net/73/futaba.php?mode=cat"),
                Bookmark("ホロライブ", "https://dec.2chan.net/84/futaba.php?mode=cat"),
                Bookmark("合成音声", "https://dec.2chan.net/81/futaba.php?mode=cat"),
                Bookmark("3DCG", "https://dat.2chan.net/x/futaba.php?mode=cat"),
                Bookmark("人工知能", "https://dec.2chan.net/85/futaba.php?mode=cat"),
                Bookmark("政治", "https://nov.2chan.net/35/futaba.php?mode=cat"),
                Bookmark("経済", "https://nov.2chan.net/36/futaba.php?mode=cat"),
                Bookmark("宗教", "https://dec.2chan.net/79/futaba.php?mode=cat"),
                Bookmark("尹錫悦", "https://dat.2chan.net/38/futaba.php?mode=cat"),
                Bookmark("岸田文雄", "https://dec.2chan.net/80/futaba.php?mode=cat"),
                Bookmark("三次実況", "https://dec.2chan.net/50/futaba.php?mode=cat"),
                Bookmark("軍", "https://cgi.2chan.net/f/futaba.php?mode=cat"),
                Bookmark("軍裏", "https://may.2chan.net/39/futaba.php?mode=cat"),
                Bookmark("数学", "https://cgi.2chan.net/m/futaba.php?mode=cat"),
                Bookmark("flash", "https://cgi.2chan.net/i/futaba.php?mode=cat"),
                Bookmark("壁紙", "https://cgi.2chan.net/k/futaba.php?mode=cat"),
                Bookmark("壁紙二", "https://dat.2chan.net/l/futaba.php?mode=cat"),
                Bookmark("東方", "https://may.2chan.net/40/futaba.php?mode=cat"),
                Bookmark("東方裏", "https://dec.2chan.net/55/futaba.php?mode=cat"),
                Bookmark("お絵かき", "https://zip.2chan.net/p/futaba.php?mode=cat"),
                Bookmark("落書き", "https://nov.2chan.net/q/futaba.php?mode=cat"),
                Bookmark("落書き裏", "https://cgi.2chan.net/u/futaba.php?mode=cat"),
                Bookmark("ニュース表", "https://zip.2chan.net/6/futaba.php?mode=cat"),
                Bookmark("昭和", "https://dec.2chan.net/76/futaba.php?mode=cat"),
                Bookmark("平成", "https://dec.2chan.net/77/futaba.php?mode=cat"),
                Bookmark("発電", "https://dec.2chan.net/53/futaba.php?mode=cat"),
                Bookmark("自然災害", "https://dec.2chan.net/52/futaba.php?mode=cat"),
                Bookmark("コロナ", "https://dec.2chan.net/83/futaba.php?mode=cat"),
                Bookmark("雑談", "https://img.2chan.net/9/futaba.php?mode=cat"),
                Bookmark("新板提案", "https://dec.2chan.net/70/futaba.php?mode=cat"),
                Bookmark("IPv6", "https://ipv6.2chan.net/54/futaba.php?mode=cat"),
                Bookmark("レイアウト", "https://may.2chan.net/layout/futaba.php?mode=cat"),
                Bookmark("お絵sql", "https://jun.2chan.net/oe/futaba.php?mode=cat"),
                Bookmark("お絵sqlip", "https://jun.2chan.net/72/futaba.php?mode=cat"),
                Bookmark("準備", "https://jun.2chan.net/junbi/futaba.php?mode=cat"),
            )
            saveBookmarks(context, bookmarks) // Save default if none exist
        }
        return bookmarks
    }

    fun addBookmark(context: Context, bookmark: Bookmark) {
        val bookmarks = getBookmarks(context)
        if (!bookmarks.any { it.url == bookmark.url }) {
            bookmarks.add(bookmark)
            saveBookmarks(context, bookmarks)
        }
    }

    fun updateBookmark(context: Context, oldBookmarkUrl: String, newBookmark: Bookmark) {
        val bookmarks = getBookmarks(context)
        val index = bookmarks.indexOfFirst { it.url == oldBookmarkUrl }
        if (index != -1) {
            bookmarks[index] = newBookmark
            saveBookmarks(context, bookmarks)
        }
    }

    fun deleteBookmark(context: Context, bookmark: Bookmark) {
        val bookmarks = getBookmarks(context)
        bookmarks.removeAll { it.url == bookmark.url }
        saveBookmarks(context, bookmarks)
    }

    fun saveSelectedBookmarkUrl(context: Context, url: String?) {
        val prefs = getPreferences(context)
        prefs.edit().putString(KEY_SELECTED_BOOKMARK_URL, url).apply()
    }

    fun getSelectedBookmarkUrl(context: Context): String {
        val prefs = getPreferences(context)
        // Get the list of bookmarks; this ensures defaults are created if the list is empty.
        val existingBookmarks = getBookmarks(context)
        val defaultUrl = existingBookmarks.firstOrNull()?.url ?: "https://may.2chan.net/b/futaba.php?mode=cat&sort=3"
        return prefs.getString(KEY_SELECTED_BOOKMARK_URL, defaultUrl)!!
    }
}
