package com.valoser.futaburakari

import android.content.Context

/**
 * リストのスクロール状態を `SharedPreferences` に保存・復元するためのストア。
 * URL をキーに、先頭可視アイテムの位置・ピクセルオフセットに加えて、安定キーとなる `anchorId` も保持します。
 * 旧仕様で保存されたキー（スキーム無しのキー形式）にもフォールバックして読み出します。
 */
class ScrollPositionStore(context: Context) {
    // スクロール位置専用の SharedPreferences。名前は "scroll_position_prefs"。
    private val prefs = context.getSharedPreferences("scroll_position_prefs", Context.MODE_PRIVATE)

    companion object {
        // URL ごとにキーを組み立てるための接頭辞
        private const val KEY_PREFIX_POSITION = "scroll_pos_pos_"
        private const val KEY_PREFIX_OFFSET = "scroll_pos_off_"
        private const val KEY_PREFIX_ANCHOR = "scroll_pos_anchor_"
    }

    /**
     * 保存済みのスクロール状態。`position`/`offset` に加えて、安定キーとなる `anchorId` を保持する。
     * `anchorId` はスクロール位置に相当する `DetailContent` の ID。
     */
    data class SavedScrollState(
        val position: Int = 0,
        val offset: Int = 0,
        val anchorId: String? = null,
    )

    /**
     * リストのスクロール状態（先頭アイテムの位置とオフセット）を保存します。
     * @param url 一意のキーとして使用する URL
     * @param position 先頭に表示されているアイテムの位置（0 始まり）
     * @param offset 先頭アイテムの上端から表示領域上端までのピクセル単位のオフセット
     * @param anchorId 先頭に表示されているアイテムの安定キー（DetailContent.id 等）
     */
    fun saveScrollState(url: String, position: Int, offset: Int, anchorId: String?) {
        val editor = prefs.edit()
            .putInt(KEY_PREFIX_POSITION + url, position.coerceAtLeast(0))
            .putInt(KEY_PREFIX_OFFSET + url, offset.coerceAtLeast(0))
        if (!anchorId.isNullOrBlank()) {
            editor.putString(KEY_PREFIX_ANCHOR + url, anchorId)
        } else {
            editor.remove(KEY_PREFIX_ANCHOR + url)
        }
        editor.apply()
    }

    /**
     * 保存されたスクロール状態を取得します。
     * @param url 取得したいスクロール状態の URL
     * @return 保存済みの `SavedScrollState`。
     * 保存が存在しない場合はスキーム無しの旧キーへフォールバックし、
     * それでも見つからなければ position/offset を 0 とした状態を返します。
     */
    fun getScrollState(url: String): SavedScrollState {
        var position = prefs.getInt(KEY_PREFIX_POSITION + url, Int.MIN_VALUE)
        var offset = prefs.getInt(KEY_PREFIX_OFFSET + url, Int.MIN_VALUE)
        var anchorId: String? = prefs.getString(KEY_PREFIX_ANCHOR + url, null)
        if (position == Int.MIN_VALUE || offset == Int.MIN_VALUE) {
            // 旧キー（スキーム無し形式）へのフォールバック
            val legacy = try { UrlNormalizer.legacyThreadKey(url) } catch (_: Exception) { url }
            position = prefs.getInt(KEY_PREFIX_POSITION + legacy, Int.MIN_VALUE)
            offset = prefs.getInt(KEY_PREFIX_OFFSET + legacy, Int.MIN_VALUE)
            if (anchorId.isNullOrBlank()) {
                anchorId = prefs.getString(KEY_PREFIX_ANCHOR + legacy, null)
            }
        }
        if (position == Int.MIN_VALUE || offset == Int.MIN_VALUE) {
            return SavedScrollState()
        }
        return SavedScrollState(
            position = position.coerceAtLeast(0),
            offset = offset.coerceAtLeast(0),
            anchorId = anchorId
        )
    }
}
