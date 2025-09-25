package com.valoser.futaburakari.cache

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * `DetailCacheManager` をアクティビティや Hilt 非対応のクラス（Worker/Compose など）から
 * 取得するための `EntryPoint`。アプリケーションコンテキスト経由で解決する。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DetailCacheManagerEntryPoint {
    fun detailCacheManager(): DetailCacheManager
}

/**
 * Hilt が直接インジェクションできない場所（Compose、WorkManager など）から
 * `DetailCacheManager` を取得するヘルパー。`EntryPointAccessors.fromApplication` を利用する。
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
