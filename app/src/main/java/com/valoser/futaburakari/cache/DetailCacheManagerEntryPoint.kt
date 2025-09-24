package com.valoser.futaburakari.cache

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * `DetailCacheManager` をアクティビティ外から取得するための Hilt エントリーポイント。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DetailCacheManagerEntryPoint {
    fun detailCacheManager(): DetailCacheManager
}

/**
 * Hilt が直接インジェクションできない場所（Compose など）から `DetailCacheManager` を取得するヘルパー。
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
