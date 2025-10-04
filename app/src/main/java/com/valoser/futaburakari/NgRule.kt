package com.valoser.futaburakari

/**
 * NG（除外）対象を表すルール定義。
 *
 * @property id 一意のルール識別子。
 * @property type ルールの適用対象種別（ID／本文／スレタイ）。
 * @property pattern 照合に用いるパターン文字列（`match` に応じて解釈）。
 * @property match 照合方式。未指定（null）の場合はルール種別ごとに既定値（ID は EXACT、本文／タイトルは SUBSTRING）が適用されます。
 * @property createdAt 作成時刻（エポックミリ秒）。不明な場合は null。
 * @property sourceKey ルールの由来を示すキー（インポート元など）。不要なら null。
 * @property ephemeral 一時的なルールかどうか。未指定（null）は不定を表します。
 */
data class NgRule(
    val id: String,
    val type: RuleType,
    val pattern: String,
    val match: MatchType? = null,
    val createdAt: Long? = null,
    val sourceKey: String? = null,
    val ephemeral: Boolean? = null
)

/**
 * ルールの対象種別。
 * - ID: 投稿の ID など識別子に対するルール。
 * - BODY: 投稿本文に対するルール。
 * - TITLE: スレッドタイトルに対するルール。
 */
enum class RuleType { ID, BODY, TITLE }

/**
 * パターン照合方式。
 * - EXACT: 完全一致（ID／本文は大文字小文字を無視、タイトルは区別）。
 * - PREFIX: 先頭一致（大文字小文字を無視）。
 * - SUBSTRING: 部分一致（大文字小文字を無視）。
 * - REGEX: 正規表現一致（大文字小文字を無視、ただし正規表現のフラグに依存）。
 */
enum class MatchType { EXACT, PREFIX, SUBSTRING, REGEX }
