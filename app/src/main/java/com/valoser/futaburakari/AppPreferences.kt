package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized accessors for app preferences backed by `SharedPreferences`.
 * Stores and retrieves `pthc`, `pwd`, and the GUID append toggle.
 */
object AppPreferences {
    /** Name of the SharedPreferences file. */
    private const val PREFS_NAME = "futaba_prefs"
    /** Key for the `pthc` value. */
    private const val KEY_PTHC = "pthc"
    /** Key for the `pwd` value. */
    private const val KEY_PWD = "pwd"
    /** Key for the flag that appends a GUID. */
    private const val KEY_APPEND_GUID = "append_guid_on"
    /** Key for the global concurrency level (1..8). */
    private const val KEY_CONCURRENCY_LEVEL = "concurrency_level"

    /**
     * Returns the app's private SharedPreferences instance.
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves the stored `pthc` value, or `null` if not set.
     */
    fun getPthc(context: Context): String? {
        return getPreferences(context).getString(KEY_PTHC, null)
    }

    /**
     * Persists the given `pthc` value.
     */
    fun savePthc(context: Context, pthc: String) {
        getPreferences(context).edit().putString(KEY_PTHC, pthc).apply()
    }

    /**
     * Retrieves the stored `pwd` value, or `null` if not set.
     */
    fun getPwd(context: Context): String? {
        return getPreferences(context).getString(KEY_PWD, null)
    }

    /**
     * Persists the given `pwd` value.
     */
    fun savePwd(context: Context, pwd: String) {
        getPreferences(context).edit().putString(KEY_PWD, pwd).apply()
    }

    /**
     * Returns whether appending a GUID is enabled.
     * Defaults to `true` when no value has been stored.
     */
    fun getAppendGuidOn(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_APPEND_GUID, true)
    }

    /**
     * Enables or disables appending a GUID.
     */
    fun setAppendGuidOn(context: Context, enabled: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_APPEND_GUID, enabled).apply()
    }

    /**
     * Gets the user-selected global concurrency level (1..8).
     * Defaults to 3 when unset or out of range.
     */
    fun getConcurrencyLevel(context: Context): Int {
        val raw = getPreferences(context).getInt(KEY_CONCURRENCY_LEVEL, 2)
        return raw.coerceIn(1, 8)
    }

    /**
     * Saves the global concurrency level (clamped to 1..8).
     */
    fun setConcurrencyLevel(context: Context, level: Int) {
        val v = level.coerceIn(1, 8)
        getPreferences(context).edit().putInt(KEY_CONCURRENCY_LEVEL, v).apply()
    }

    /**
     * Generates a new random 8-digit numeric password as a String.
     * Note: uses non-cryptographic randomness.
     */
    fun generateNewPwd(): String {
        return (10000000..99999999).random().toString()
    }
}
