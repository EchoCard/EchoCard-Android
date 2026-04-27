package com.vaca.callmate.features.outbound

/**
 * 与 iOS `CallSessionController.RuleChangeItem` / `RuleChangeRequest` 对齐。
 */
data class RuleChangeItem(
    val id: String,
    val type: String,
    val rule: String,
    val action: String,
)

data class RuleChangeRequest(
    val id: String,
    val originalRule: String,
    val updatedRuleSummary: String,
    val updatedRules: List<RuleChangeItem>,
)
