package com.devmind.common.agent.gateway;

/**
 * 智能体网关 SPI（CAP-76 FR-01）：bot 与 copilot 的唯一耦合面，bot 只依赖 common。
 */
public interface AgentGateway {

    /** 裸文本一轮：确认/取消指令、写操作确认卡、模型工具循环都从这里进。 */
    AgentReply handle(AgentRequest request);

    /** 卡片按钮回调；同步执行，卡片更新内容放在返回的 {@link AgentReply#card()}（null = 不更新）。 */
    AgentReply onCardAction(AgentCardAction action);

    /** {@code /cancel [短码]}；短码为空 = 取消该 sender 当前最近的待确认单。 */
    AgentReply cancel(AgentRequest request, String shortCode);

    /** {@code /new}：归档该 sender 的智能体会话上下文（默认 no-op）。 */
    default void reset(AgentRequest request) {
    }

    /** 供 bot 判断是否接管裸文本（{@code devmind.copilot.enabled=false} 时回落问答）。 */
    default boolean enabled() {
        return true;
    }

    /**
     * 是否先回「正在处理…」进度卡（FR-25；bot 侧执行，卡片能力由适配器决定）。
     * 默认开启；copilot 映射 {@code devmind.copilot.reply-progress-card}。
     */
    default boolean replyProgressCard() {
        return true;
    }
}
