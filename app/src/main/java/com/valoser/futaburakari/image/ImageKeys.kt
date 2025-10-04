package com.valoser.futaburakari.image

import coil3.memory.MemoryCache

/**
 * Coil 3 のメモリキャッシュキーを一貫して生成するユーティリティ。
 * 用途ごとにプレフィックスを分け、同じ URL でも目的に応じたキャッシュを切り替えられるようにする。
 */
object ImageKeys {
    /**
     * サムネイル（一覧表示）用のメモリキャッシュキーを返す。
     * `media_thumb:` プレフィックスでフルサイズとの差分を明示している。
     */
    fun thumb(url: String): MemoryCache.Key = MemoryCache.Key("media_thumb:$url")

    /**
     * 原寸（詳細表示）用のメモリキャッシュキーを返す。
     * `media_full:` プレフィックスでサムネイルやプリフェッチと衝突しないようにする。
     */
    fun full(url: String): MemoryCache.Key = MemoryCache.Key("media_full:$url")

    /**
     * 将来的な中間サイズや事前取得の識別用キー。
     * `media_prefetch:` プレフィックスを持たせ、実レイアウトと分離したプリフェッチ用途として扱うことを想定。
     */
    fun prefetch(url: String): MemoryCache.Key = MemoryCache.Key("media_prefetch:$url")
}
