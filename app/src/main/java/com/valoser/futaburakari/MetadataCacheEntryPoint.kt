package com.valoser.futaburakari

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt 管理外からメタデータキャッシュを取得するための EntryPoint。
 *
 * `MetadataExtractor` のような static オブジェクトや、通常の依存注入を受けない
 * Activity ライフサイクル経路からでも、アプリケーションスコープの
 * `MetadataCache` を共有利用できるようにする。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MetadataCacheEntryPoint {
    fun metadataCache(): MetadataCache

    companion object {
        fun resolve(context: android.content.Context): MetadataCache {
            val appContext = context.applicationContext
            return EntryPointAccessors.fromApplication(appContext, MetadataCacheEntryPoint::class.java)
                .metadataCache()
        }
    }
}
