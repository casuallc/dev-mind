package com.devmind.notification.controller;

import com.devmind.notification.dto.PrefsRequest;
import com.devmind.notification.dto.PrefsView;
import com.devmind.notification.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知偏好（FR-05 防打扰）：免打扰时段 + 静默事件/会话。
 */
@RestController
@RequestMapping("/api/notification-prefs")
public class NotificationPrefsController {

    private final NotificationService service;

    public NotificationPrefsController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PrefsView get() {
        return service.getPrefs();
    }

    @PutMapping
    public PrefsView update(@RequestBody PrefsRequest req) {
        return service.updatePrefs(req);
    }
}
