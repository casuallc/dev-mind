package com.devmind.bot.spi;

/**
 * 入站消息回调（CAP-75）：底座实现，适配器在事件到达时调用。
 * 约定：实现必须快速返回（业务在底座异步线程池执行），适配器调用方不能阻塞等待答案。
 */
public interface BotMessageHandler {

    void onMessage(BotMessage message);

    /**
     * 卡片按钮回调（CAP-76 FR-24）：默认 no-op，既有实现零改动。
     * 与 onMessage 同约定：必须快速返回（去重后投递异步执行器，3s ACK 由事件线程返回）。
     */
    default void onCardAction(BotCardAction action) {
    }
}
