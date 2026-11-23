package com.devmind.common.agent;

import java.time.Instant;
import java.util.List;

/**
 * CAP-115 agent 审计桥 SPI（session 等模块 → agent 模块：读/补记指令审计）。
 * 接口定义在 common（同 AgentNodeConnector 先例），实现方 devmind-agent 由调用方以
 * {@code ObjectProvider<AgentAuditBridge>} 探测注入——未装配 agent 模块时
 * 历史查询返回空、补记静默跳过。
 */
public interface AgentAuditBridge {

    /**
     * 一条指令审计的只读视图（不含凭证字段——detail 红线「禁凭证」由写入方保证）。
     *
     * @param createdAt  记录时间
     * @param event      分类内事件（TERMINAL_EXEC/WS_FILE_WRITE/…）
     * @param nodeName   节点名快照
     * @param actor      操作人（WS 交互多为空；二次确认补记非空）
     * @param target     目标（终端工作区 = workspaceKey）
     * @param result     OK / FAIL / REJECTED
     * @param durationMs 带 ack 指令等待耗时
     * @param detail     摘要（命令全文/错误文案等）
     */
    record Entry(Instant createdAt, String event, String nodeName, String actor, String target,
                 String result, Long durationMs, String detail) {
    }

    /** 按 target（终端工作区 = workspaceKey）倒序查 COMMAND 类审计，limit 截断（上限 200）。 */
    default List<Entry> recentCommandsByTarget(String target, int limit) {
        return List.of();
    }

    /**
     * 补记一条带操作人的 COMMAND 审计（如终端危险命令经前端二次确认放行——confirm 语义
     * 不在 WS 交互里，需要调用方显式补记确认人）。默认 no-op（agent 未装配）。
     */
    default void recordCommand(String event, String nodeId, String target, boolean ok,
                               String actor, String detail) {
    }
}
