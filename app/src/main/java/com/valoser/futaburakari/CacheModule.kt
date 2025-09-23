package com.valoser.futaburakari

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * キャッシュ関連の依存を提供する Hilt モジュール。
 */
@Module
@InstallIn(SingletonComponent::class)
object CacheModule {
    @Provides
    @Singleton
    fun provideMetadataCache(
        @ApplicationContext context: Context,
    ): MetadataCache {
        return MetadataCache(context)
    }
}
