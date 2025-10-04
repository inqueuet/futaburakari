package com.valoser.futaburakari

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt 管理外からアプリケーションスコープの `MetadataCache` を解決する EntryPoint。
 *
 * `MetadataExtractor` のような Kotlin `object` や、通常の依存注入を受けない
 * Activity ライフサイクル経路からでも、`EntryPointAccessors.fromApplication`
 * を介して同一インスタンスを共有利用できるようにする。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MetadataCacheEntryPoint {
    fun metadataCache(): MetadataCache

    companion object {
        /**
         * 渡された `Context` からアプリケーションコンテキストを取得し、EntryPoint 経由で
         * シングルトンの `MetadataCache` を解決する。
         * Activity 由来の `Context` を直接保持しないことでリークを避ける。
         */
        fun resolve(context: android.content.Context): MetadataCache {
            val appContext = context.applicationContext
            return EntryPointAccessors.fromApplication(appContext, MetadataCacheEntryPoint::class.java)
                .metadataCache()
        }
    }
}
