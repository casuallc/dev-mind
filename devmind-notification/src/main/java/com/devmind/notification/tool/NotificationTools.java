package com.devmind.notification.tool;

import com.devmind.common.agent.tool.PlatformTool;
import com.devmind.common.agent.tool.ToolResult;
import com.devmind.common.agent.tool.ToolRisk;
import com.devmind.common.agent.tool.ToolSpec;
import com.devmind.common.agent.tool.ToolText;
import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.service.NotificationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CAP-76 M6 通知域工具：通知中心查询、未读数、标记已读。
 * 通知中心是平台级数据（当前实现无收件人维度），无需项目上下文。
 */
@Configuration
public class NotificationTools {

    private static final String M = "CAP-76 M6";
    private static final List<String> LEVELS = List.of("P0", "P1", "P2");

    private final NotificationService notifications;

    public NotificationTools(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Bean
    public PlatformTool listNotificationsTool() {
        ToolSpec spec = new ToolSpec("list_notifications", "查询通知列表",
                "查询通知中心的通知（分级 P0/P1/P2、标题、正文摘要、关联对象、已读状态）。"
                        + "unreadOnly=true 只看未读；level 可过滤分级。用于回答「最近有什么通知/有没有未读」。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "level": {"type": "string", "enum": ["P0", "P1", "P2"]},
                            "unreadOnly": {"type": "boolean", "default": false},
                            "limit": {"type": "integer", "default": 20, "minimum": 1, "maximum": 50}
                          }
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-notification", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            String level = normalizeLevel(ToolText.str(args, "level"));
            boolean unreadOnly = Boolean.TRUE.equals(args.get("unreadOnly"));
            int limit = ToolText.limit(args);
            List<NotificationView> list = notifications.list(level, unreadOnly, limit);
            long unread = notifications.unreadCount();
            if (list.isEmpty()) {
                return ToolResult.ok("没有匹配的通知（当前未读 " + unread + " 条）。");
            }
            StringBuilder sb = new StringBuilder("当前未读 " + unread + " 条；匹配 " + list.size() + " 条：");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (NotificationView n : list) {
                sb.append("\n- [").append(n.level()).append("] ").append(n.title())
                        .append(" · ").append(ToolText.time(n.createdAt()))
                        .append(n.readAt() == null ? " · 未读" : " · 已读");
                if (n.body() != null && !n.body().isBlank()) {
                    sb.append("\n  ").append(ToolText.oneLine(n.body(), 200));
                }
                if (n.entityType() != null && n.entityId() != null) {
                    sb.append("\n  关联: ").append(n.entityType()).append("=").append(n.entityId());
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", n.id());
                row.put("level", String.valueOf(n.level()));
                row.put("title", n.title());
                row.put("eventType", n.eventType());
                row.put("read", n.readAt() != null);
                if (n.entityType() != null) {
                    row.put("entityType", n.entityType());
                }
                if (n.entityId() != null) {
                    row.put("entityId", n.entityId());
                }
                rows.add(row);
            }
            return ToolResult.ok(sb.toString(), sb.toString(),
                    ToolResult.data("unread", unread, "notifications", rows));
        });
    }

    @Bean
    public PlatformTool countUnreadNotificationsTool() {
        ToolSpec spec = new ToolSpec("count_unread_notifications", "查询未读通知数",
                "返回通知中心当前未读条数，用于回答「有多少未读通知」。",
                """
                        {
                          "type": "object",
                          "properties": {}
                        }
                        """,
                ToolRisk.READ, "VIEWER", false, "devmind-notification", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            long unread = notifications.unreadCount();
            String text = "当前有 " + unread + " 条未读通知。";
            return ToolResult.ok(text, text, ToolResult.data("unread", unread));
        });
    }

    @Bean
    public PlatformTool markNotificationsReadTool() {
        ToolSpec spec = new ToolSpec("mark_notifications_read", "标记通知已读",
                "把一条通知（id）或全部未读通知标记为已读。id 与 all 二选一；all=true 时忽略 id。"
                        + "调用后生成确认单，用户确认后才执行。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "id": {"type": "integer", "minimum": 1},
                            "all": {"type": "boolean", "default": false}
                          }
                        }
                        """,
                ToolRisk.WRITE, "DEVELOPER", false, "devmind-notification", M);
        return PlatformTool.of(spec, (call, ctx) -> {
            Map<String, Object> args = call.args();
            boolean all = Boolean.TRUE.equals(args.get("all"));
            if (all) {
                int n = notifications.markAllRead();
                String text = "已将 " + n + " 条未读通知标记为已读";
                return ToolResult.ok(text, text, ToolResult.data("marked", n));
            }
            Object idArg = args.get("id");
            if (!(idArg instanceof Number n)) {
                return ToolResult.fail(ToolResult.INVALID_ARGUMENTS,
                        "请提供通知 id，或设置 all=true 标记全部未读");
            }
            long id = n.longValue();
            notifications.markRead(id);
            String text = "已将通知 " + id + " 标记为已读";
            return ToolResult.ok(text, text, ToolResult.data("id", id));
        });
    }

    private static String normalizeLevel(String level) {
        if (level == null) {
            return null;
        }
        String s = level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(s)) {
            throw new IllegalArgumentException("level 仅支持 P0/P1/P2");
        }
        return s;
    }
}
