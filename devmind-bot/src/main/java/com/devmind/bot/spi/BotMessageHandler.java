package com.devmind.bot.spi;

/**
 * 入站消息回调（CAP-75）：底座实现，适配器在事件到达时调用。
 * 约定：实现必须快速返回（业务在底座异步线程池执行），适配器调用方不能阻塞等待答案。
 */
public interface BotMessageHandler {

    void onMessage(BotMessage message);
}
