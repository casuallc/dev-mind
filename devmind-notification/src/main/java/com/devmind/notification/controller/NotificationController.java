package com.devmind.notification.controller;

import com.devmind.notification.dto.ActionRequest;
import com.devmind.notification.dto.EmitRequest;
import com.devmind.notification.dto.NotificationView;
import com.devmind.notification.service.NotificationService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知中心 REST（CAP-06 FR-06）：列表/未读数/已读/快捷动作 + 测试 emit。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationView> list(@RequestParam(required = false) String level,
                                       @RequestParam(defaultValue = "false") boolean unreadOnly,
                                       @RequestParam(defaultValue = "200") int limit) {
        return service.list(level, unreadOnly, limit);
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount() {
        return Map.of("count", service.unreadCount());
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id) {
        service.markRead(id);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll() {
        return Map.of("count", service.markAllRead());
    }

    /** 快捷动作（FR-04）：authorize / deny / finish / view。 */
    @PostMapping("/{id}/action")
    public NotificationView action(@PathVariable Long id, @RequestBody ActionRequest req) {
        return service.action(id, req.action());
    }

    /** 测试/调试 emit。 */
    @PostMapping("/emit")
    public NotificationView emit(@RequestBody EmitRequest req) {
        return service.emitTest(req);
    }
}
