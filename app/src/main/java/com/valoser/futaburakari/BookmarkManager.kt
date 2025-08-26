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
                Bookmark("may", "https://may.2chan.net/b/futaba.php?mode=cat&sort=3")
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
