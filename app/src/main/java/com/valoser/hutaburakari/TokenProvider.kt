package com.valoser.hutaburakari

interface TokenProvider {
    fun prepare(userAgent: String? = null)
    suspend fun fetchTokens(postPageUrl: String): Result<Map<String, String>>
}