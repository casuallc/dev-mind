package com.devmind.bot.spi;

import com.devmind.common.agent.gateway.AgentCard;

/**
 * Bot 平台适配器 SPI（CAP-75 FR-04）：一个实现 = 一个 bot 平台（飞书/企微/钉钉…）。
 * 与通知中心 NotificationChannel 同套路：Spring 收集全部实现按 code 注册，
 * {@code BotAdapterRegistry}（SmartLifecycle）随应用启停统一驱动。
 *
 * <p>实现只负责协议面：接入事件（长连接/webhook）→ 归一化为 {@link BotMessage} 回调 handler；
 * 把底座产出的回复文本发回平台。不得碰业务服务。</p>
 */
public interface BotAdapter {

    /** 平台 code（feishu/wecom/…），路由按它找回适配器做回推。 */
    String code();

    /**
     * 启动接入（建连/注册回调），事件到达时回调 handler。
     * 实现必须容忍配置缺失：降级为禁用 + 告警日志，不得抛异常拖垮应用启动。
     */
    void start(BotMessageHandler handler);

    /** 停止接入（断连/注销），应用关闭时调用。 */
    void stop();

    /** 把回复文本发回消息来源会话。失败抛异常由底座兜底记日志（不再重试）。 */
    void reply(BotMessage origin, String text);

    /**
     * CAP-75 FR-16：把图片发回消息来源会话（需求描述截图等出站图片）。
     * 默认 no-op——不支持图片的平台零改动；底座会在失败时补一条文字提示，
     * 实现不得静默吞错（发送失败抛异常）。
     */
    default void replyImage(BotMessage origin, String fileName, String contentType, byte[] bytes) {
        // 平台不支持图片回推：丢弃（底座日志可见）
    }

    /**
     * CAP-75 FR-17：按平台图片键下载图片字节（入站）。默认 null = 平台不支持收图，
     * 底座回「当前平台不支持发送图片给机器人」。实现不得吞错（失败抛异常由底座兜底）。
     */
    default byte[] downloadImage(String imageKey) {
        return null;
    }

    /**
     * CAP-75 FR-20：主动向会话推送文本（无入站消息 origin，如会话终态进展）。
     * 默认 no-op——不支持主动发送的平台零改动；实现不得吞错（失败抛异常由调用方记日志）。
     */
    default void send(String externalChatId, String text) {
        // 平台不支持主动推送：丢弃（调用方日志可见）
    }

    /**
     * CAP-76 FR-23：是否支持交互卡片。false（默认）时底座不发进度卡，
     * 确认单等内容走 {@link #replyCard} 的文本降级；true 的实现必须重写
     * {@link #replyCard} / {@link #updateCard} / {@link #sendCard}。
     */
    default boolean supportsCard() {
        return false;
    }

    /**
     * CAP-76 FR-23：发中性卡片，返回平台卡片消息 id（后续 {@link #updateCard} 的锚点）。
     * 默认实现退化为文本（保证不支持卡片的平台仍可用，确认内容含短码）。
     *
     * @return 卡片消息 id；null = 平台不支持卡片、内容已按文本发送（底座据此不再补发文本）
     */
    default String replyCard(BotMessage origin, AgentCard card) {
        reply(origin, card.toPlainText());
        return null;
    }

    /** CAP-76 FR-23：原地更新卡片（需平台支持 update_multi）；失败抛异常由底座降级为文本。 */
    default void updateCard(String messageId, AgentCard card) {
        // 平台不支持更新：底座回文本结果
    }

    /** CAP-76 FR-23：主动发卡（无入站消息 origin）；默认 no-op。 */
    default void sendCard(String externalChatId, AgentCard card) {
        // 平台不支持主动发卡：丢弃（调用方日志可见）
    }
}
