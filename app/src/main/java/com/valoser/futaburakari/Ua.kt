package com.valoser.futaburakari

/**
 * ネットワークリクエストで一貫して使用する固定の User-Agent。
 * 互換性の高いデスクトップ版 Chrome 相当の UA を提供します。
 */
object Ua {
    // 安定動作のためのデスクトップ Chrome 相当の固定 UA。全リクエストで一貫して使用することを想定。
    const val STRING =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
}
