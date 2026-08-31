package com.devmind.notification.action;

/**
 * 通知快捷动作处理器 SPI（FR-04）。各能力模块注册自己的实现
 * （会话层注册 SESSION 的 authorize/deny/finish，避免 notification ↔ session 循环依赖）。
 */
public interface NotificationActionHandler {

    /** 是否支持该实体类型（SESSION / PROJECT / …）。 */
    boolean supports(String entityType);

    /** 是否能处理该动作码。 */
    boolean canHandle(String action);

    /** 执行动作。 */
    void handle(String entityType, String entityId, String action);
}
