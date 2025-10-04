package com.valoser.futaburakari

/**
 * ネットワークリクエストで一貫して使用する固定の User-Agent。
 * Windows 10 / 64bit 上の Chrome 139 を偽装する UA を提供します。
 */
object Ua {
    // 安定動作のための Windows 10 x64 / Chrome 139 相当の固定 UA。全リクエストで使い回すことを想定。
    const val STRING =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
}
