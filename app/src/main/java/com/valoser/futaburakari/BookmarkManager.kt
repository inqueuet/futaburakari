package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages persistence for bookmarks and the currently selected bookmark URL.
 * Data is stored in `SharedPreferences` with the bookmark list serialized via Gson, and the list
 * is automatically seeded with a pair of default boards the first time it is requested.
 */
object BookmarkManager {

    /** Name of the SharedPreferences file used for bookmarks. */
    private const val PREFS_NAME = "com.valoser.futaburakari.bookmarks"
    /** Key under which the serialized bookmark list is stored. */
    private const val KEY_BOOKMARKS = "bookmarks_list"
    /** Key for the currently selected bookmark URL. */
    private const val KEY_SELECTED_BOOKMARK_URL = "selected_bookmark_url"

    /** Returns the preferences instance scoped to this manager. */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Serializes and saves the provided bookmark list. */
    fun saveBookmarks(context: Context, bookmarks: List<Bookmark>) {
        val prefs = getPreferences(context)
        val editor = prefs.edit()
        val gson = Gson()
        val json = gson.toJson(bookmarks)
        editor.putString(KEY_BOOKMARKS, json)
        editor.apply()
    }

    /**
     * Loads bookmarks from storage; when empty, seeds with default entries and saves them.
     */
    fun getBookmarks(context: Context): MutableList<Bookmark> {
        val prefs = getPreferences(context)
        val gson = Gson()
        val json = prefs.getString(KEY_BOOKMARKS, null)
        val type = object : TypeToken<MutableList<Bookmark>>() {}.type
        var bookmarks: MutableList<Bookmark>? = gson.fromJson(json, type)
        // If none exist, seed with two default bookmarks and persist them.
        if (bookmarks == null || bookmarks.isEmpty()) {
            bookmarks = mutableListOf(
                Bookmark("どうぶつ", "https://dat.2chan.net/d/futaba.php"),
                Bookmark("しょくぶつ", "https://zip.2chan.net/z/futaba.php"),

            )
            saveBookmarks(context, bookmarks) // Save defaults if none exist
        }
        return bookmarks
    }

    /** Adds a bookmark if another with the same URL does not already exist. */
    fun addBookmark(context: Context, bookmark: Bookmark) {
        val bookmarks = getBookmarks(context)
        if (!bookmarks.any { it.url == bookmark.url }) {
            bookmarks.add(bookmark)
            saveBookmarks(context, bookmarks)
        }
    }

    /** Replaces the bookmark matching `oldBookmarkUrl` with `newBookmark` if found. */
    fun updateBookmark(context: Context, oldBookmarkUrl: String, newBookmark: Bookmark) {
        val bookmarks = getBookmarks(context)
        val index = bookmarks.indexOfFirst { it.url == oldBookmarkUrl }
        if (index != -1) {
            bookmarks[index] = newBookmark
            saveBookmarks(context, bookmarks)
        }
    }

    /** Deletes all bookmarks whose URL equals the provided bookmark's URL. */
    fun deleteBookmark(context: Context, bookmark: Bookmark) {
        val bookmarks = getBookmarks(context)
        bookmarks.removeAll { it.url == bookmark.url }
        saveBookmarks(context, bookmarks)
    }

    /** Saves the currently selected bookmark URL; pass null to clear the selection. */
    fun saveSelectedBookmarkUrl(context: Context, url: String?) {
        val prefs = getPreferences(context)
        val editor = prefs.edit()
        if (url == null) {
            editor.remove(KEY_SELECTED_BOOKMARK_URL)
        } else {
            editor.putString(KEY_SELECTED_BOOKMARK_URL, url)
        }
        editor.apply()
    }

    /**
     * Returns the selected bookmark URL.
     * Falls back to the first bookmark when available, otherwise uses the built-in default board URL.
     * Ensures defaults are created by invoking `getBookmarks` when none exist.
     */
    fun getSelectedBookmarkUrl(context: Context): String {
        val prefs = getPreferences(context)
        // Get the list of bookmarks; this ensures defaults are created if the list is empty.
        val existingBookmarks = getBookmarks(context)
        val defaultUrl = existingBookmarks.firstOrNull()?.url ?: "https://may.2chan.net/b/futaba.php"
        return prefs.getString(KEY_SELECTED_BOOKMARK_URL, null) ?: defaultUrl
    }
}
