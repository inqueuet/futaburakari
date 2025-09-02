package com.valoser.futaburakari

data class NgRule(
    val id: String,
    val type: RuleType,
    val pattern: String,
    val match: MatchType? = null,
    val createdAt: Long? = null,
    val sourceKey: String? = null,
    val ephemeral: Boolean? = null
)

enum class RuleType { ID, BODY, TITLE }

enum class MatchType { EXACT, PREFIX, SUBSTRING, REGEX }
