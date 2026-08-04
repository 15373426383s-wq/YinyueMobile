package com.yinyue.mobile.agent

import com.yinyue.mobile.data.model.ToolCall

/** 工具执行管理器。借鉴 Operit ToolExecutionManager，从 AgentLoop 抽离工具执行职责。 */
class ToolExecutionManager(
    private val tools: ToolRegistry,
    private val approvalGate: ApprovalGate,
) {
    /**
     * 执行一批工具调用，处理权限确认与结果回填。
     * [emit] 用于向 AgentLoop 事件流发射步骤事件。
     * [agentMode] 决定真实执行还是模拟。
     * [onResult] 回调让 AgentLoop 把结果加入对话历史。
     */
    suspend fun executeCalls(
        calls: List<ToolCall>,
        agentMode: AgentMode,
        emit: suspend (AgentStep) -> Unit,
        onResult: (ToolCall, String) -> Unit,
    ) {
        for (call in calls) {
            emit(AgentStep.ToolCallStarted(call))
            // 权限三态检查（借鉴 Operit PermissionLevel）
            when (tools.effectivePermission(call.name)) {
                PermissionLevel.FORBID -> {
                    val denied = "工具 ${call.name} 已被用户禁止"
                    emit(AgentStep.ToolFinished(call, denied))
                    onResult(call, denied)
                    continue
                }
                PermissionLevel.ASK -> {
                    emit(AgentStep.WaitingApproval(call))
                    if (!approvalGate.awaitApproval()) {
                        val denied = "用户拒绝了该操作"
                        emit(AgentStep.ToolFinished(call, denied))
                        onResult(call, denied)
                        continue
                    }
                    // 用户可设为始终允许（见 ApprovalCard + ChatViewModel.alwaysAllow）
                }
                PermissionLevel.ALLOW -> { /* 直接执行 */ }
            }
            val result = if (agentMode == AgentMode.SIMULATE) {
                tools.mockExecute(call)
            } else {
                tools.execute(call)
            }
            emit(AgentStep.ToolFinished(call, result))
            onResult(call, result)
        }
    }
}