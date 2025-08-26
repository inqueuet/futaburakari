package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {
    private const val PREFS_NAME = "futaba_prefs"
    private const val KEY_PTHC = "pthc"
    private const val KEY_PWD = "pwd"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPthc(context: Context): String? {
        return getPreferences(context).getString(KEY_PTHC, null)
    }

    fun savePthc(context: Context, pthc: String) {
        getPreferences(context).edit().putString(KEY_PTHC, pthc).apply()
    }

    fun getPwd(context: Context): String? {
        return getPreferences(context).getString(KEY_PWD, null)
    }

    fun savePwd(context: Context, pwd: String) {
        getPreferences(context).edit().putString(KEY_PWD, pwd).apply()
    }

    /**
     * Generates a new random 8-digit password.
     */
    fun generateNewPwd(): String {
        return (10000000..99999999).random().toString()
    }
}
