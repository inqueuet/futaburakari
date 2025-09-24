package com.valoser.futaburakari

import android.content.Context

/**
 * リストのスクロール位置を `SharedPreferences` に保存・復元するためのストア。
 * URL をキーに、先頭可視アイテムの位置とピクセルオフセットを保持します。
 * 旧仕様で保存されたキー（スキーム無しのキー形式）にもフォールバックして読み出します。
 */
class ScrollPositionStore(context: Context) {
    // スクロール位置専用の SharedPreferences。名前は "scroll_position_prefs"。
    private val prefs = context.getSharedPreferences("scroll_position_prefs", Context.MODE_PRIVATE)

    companion object {
        // URL ごとにキーを組み立てるための接頭辞
        private const val KEY_PREFIX_POSITION = "scroll_pos_pos_"
        private const val KEY_PREFIX_OFFSET = "scroll_pos_off_"
    }

    /**
     * リストのスクロール状態（先頭アイテムの位置とオフセット）を保存します。
     * @param url 一意のキーとして使用する URL
     * @param position 先頭に表示されているアイテムの位置（0 始まり）
     * @param offset 先頭アイテムの上端から表示領域上端までのピクセル単位のオフセット
     */
    fun saveScrollState(url: String, position: Int, offset: Int) {
        prefs.edit()
            .putInt(KEY_PREFIX_POSITION + url, position)
            .putInt(KEY_PREFIX_OFFSET + url, offset)
            .apply()
    }

    /**
     * 保存されたスクロール状態を取得します。
     * @param url 取得したいスクロール状態の URL
     * @return 位置とオフセットのペア。
     * 保存が存在しない場合はスキーム無しの旧キーへフォールバックし、
     * それでも見つからなければ (0, 0) を返します。
     */
    fun getScrollState(url: String): Pair<Int, Int> {
        var position = prefs.getInt(KEY_PREFIX_POSITION + url, Int.MIN_VALUE)
        var offset = prefs.getInt(KEY_PREFIX_OFFSET + url, Int.MIN_VALUE)
        if (position == Int.MIN_VALUE || offset == Int.MIN_VALUE) {
            // 旧キー（スキーム無し形式）へのフォールバック
            val legacy = try { UrlNormalizer.legacyThreadKey(url) } catch (_: Exception) { url }
            position = prefs.getInt(KEY_PREFIX_POSITION + legacy, 0)
            offset = prefs.getInt(KEY_PREFIX_OFFSET + legacy, 0)
        }
        if (position == Int.MIN_VALUE || offset == Int.MIN_VALUE) return 0 to 0
        return position to offset
    }
}
