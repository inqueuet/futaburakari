package com.valoser.futaburakari.cache

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt が直接インジェクトできないクラス（Worker、Compose ランタイム、サービスなど）から
 * `DetailCacheManager` を取得するための `EntryPoint`。シングルトンスコープで提供される。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DetailCacheManagerEntryPoint {
    fun detailCacheManager(): DetailCacheManager
}

/**
 * 任意の `Context` からアプリケーションコンテキストを取り出し、`EntryPointAccessors.fromApplication`
 * を介して `DetailCacheManager` を取得するヘルパー。
 */
object DetailCacheManagerProvider {
    fun get(context: Context): DetailCacheManager {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            DetailCacheManagerEntryPoint::class.java
        )
        return entryPoint.detailCacheManager()
    }
}
