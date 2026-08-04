package com.yinyue.mobile.agent

/** Agent 运行模式。参考 Codex CLI / WorkBuddy 的 Ask-Plan-Craft 三层模型。 */
enum class AgentMode(val label: String) {
    /** 纯问答：不截图、不调工具，直接 LLM 对话 */
    ASK("问答"),
    /** 仅计划：感知 + 决策，产出执行计划后暂停，不执行工具 */
    PLAN("计划"),
    /** 模拟执行：走完整循环但工具返回 mock 结果，不碰系统 */
    SIMULATE("模拟"),
    /** 完整执行：感知 → 决策 → 执行 → 验证，全链路 */
    FULL("完整"),
}
