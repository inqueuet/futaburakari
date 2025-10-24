package com.valoser.futaburakari

/**
 * スレ監視用に登録されたキーワードを表すエントリ。
 *
 * @property id エントリのユニーク ID。
 * @property keyword 照合に使用するキーワード（部分一致／大文字小文字を区別しない）。
 * @property createdAt 登録時刻（ミリ秒）。
 */
data class ThreadWatchEntry(
    val id: String,
    val keyword: String,
    val createdAt: Long = System.currentTimeMillis(),
)
