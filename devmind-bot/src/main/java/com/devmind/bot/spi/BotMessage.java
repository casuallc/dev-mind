package com.devmind.bot.spi;

/**
 * 标准化入站消息（CAP-75 FR-05）：各 bot 平台适配器把原生事件归一化为此结构交给底座路由。
 * 底座不感知平台协议；适配器不感知业务。
 *
 * @param channel        平台 code（feishu/wecom/…，与 {@link BotAdapter#code()} 对应）
 * @param externalChatId 平台侧会话 id（飞书 chat_id；绑定表唯一键的一半）
 * @param chatType       {@link #CHAT_TYPE_P2P} | {@link #CHAT_TYPE_GROUP}
 * @param senderId       平台侧发送者 id（飞书 open_id）
 * @param text           纯文本正文（适配器负责剥离 @机器人 提及）
 * @param mentioned      群聊里是否 @ 了机器人（群聊只响应 mentioned=true；私聊恒处理）
 * @param messageId      平台侧消息 id（reply API 的锚点）
 * @param eventId        平台事件 id（底座去重键；可空 = 不去重）
 * @param imageKey       CAP-75 FR-17 入站图片：平台侧图片键（飞书 image_key；text 消息恒 null）。
 *                       图片消息 text 为 null——底座在 botExecutor 里惰性下载，不占事件回调窗口
 */
public record BotMessage(String channel, String externalChatId, String chatType,
                         String senderId, String text, boolean mentioned,
                         String messageId, String eventId, String imageKey) {

    public static final String CHAT_TYPE_P2P = "p2p";
    public static final String CHAT_TYPE_GROUP = "group";

    /** 兼容构造器：FR-17 之前的文本消息调用点（imageKey=null）。 */
    public BotMessage(String channel, String externalChatId, String chatType, String senderId,
                      String text, boolean mentioned, String messageId, String eventId) {
        this(channel, externalChatId, chatType, senderId, text, mentioned, messageId, eventId, null);
    }
}
