package com.valoser.hutaburakari

import android.content.Context

class ScrollPositionStore(context: Context) {

    private val prefs = context.getSharedPreferences("scroll_position_prefs", Context.MODE_PRIVATE)

    companion object {
        // キーの一部として使うプレフィックス
        private const val KEY_PREFIX_POSITION = "scroll_pos_pos_"
        private const val KEY_PREFIX_OFFSET = "scroll_pos_off_"
    }

    /**
     * RecyclerViewのスクロール状態（先頭アイテムの位置とオフセット）を保存します。
     * @param url 一意のキーとして使用するURL
     * @param position 先頭に表示されているアイテムのAdapter内での位置
     * @param offset 先頭アイテムのビューの上端からRecyclerViewの上端までのピクセル単位のオフセット
     */
    fun saveScrollState(url: String, position: Int, offset: Int) {
        prefs.edit()
            .putInt(KEY_PREFIX_POSITION + url, position)
            .putInt(KEY_PREFIX_OFFSET + url, offset)
            .apply()
    }

    /**
     * 保存されたRecyclerViewのスクロール状態を取得します。
     * @param url 取得したいスクロール状態のURL
     * @return アイテムの位置とオフセットのペア。保存された値がなければ (0, 0) を返す。
     */
    fun getScrollState(url: String): Pair<Int, Int> {
        val position = prefs.getInt(KEY_PREFIX_POSITION + url, 0)
        val offset = prefs.getInt(KEY_PREFIX_OFFSET + url, 0)
        return Pair(position, offset)
    }
}