package com.devmind.notification.dto;

import com.devmind.notification.model.NotificationLevel;
import java.util.List;

/**
 * 通知的快捷动作定义（FR-04）。
 *
 * @param action 动作码：authorize / deny / finish / view / …
 * @param label  展示文案：允许授权 / 拒绝 / 结束会话 / 查看会话
 */
public record ActionDef(String action, String label) {
}
