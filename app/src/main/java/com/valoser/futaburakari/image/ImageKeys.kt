package com.valoser.futaburakari.image

import coil3.memory.MemoryCache

/**
 * Coil のメモリキャッシュキーを一貫して生成するユーティリティ。
 * サムネイル（一覧）と原寸（詳細/拡大）でキーを分け、画面間でのキャッシュ共有を安定させる。
 */
object ImageKeys {
    /** サムネイル用のメモリキャッシュキーを返す。 */
    fun thumb(url: String): MemoryCache.Key = MemoryCache.Key("media_thumb:$url")

    /** 原寸（詳細表示）用のメモリキャッシュキーを返す。 */
    fun full(url: String): MemoryCache.Key = MemoryCache.Key("media_full:$url")

    /**
     * 将来的な中間サイズや事前取得の識別用キー。
     * 実レイアウトと分離したプリフェッチ用途に利用することを想定。
     */
    fun prefetch(url: String): MemoryCache.Key = MemoryCache.Key("media_prefetch:$url")
}
