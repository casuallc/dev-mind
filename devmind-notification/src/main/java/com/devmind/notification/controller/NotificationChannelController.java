package com.devmind.notification.controller;

import com.devmind.notification.dto.ChannelRequest;
import com.devmind.notification.dto.ChannelView;
import com.devmind.notification.service.NotificationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知通道配置（FR-03 通道插件化）：启用开关 / 分级阈值 / 通道专属配置。
 */
@RestController
@RequestMapping("/api/notification-channels")
public class NotificationChannelController {

    private final NotificationService service;

    public NotificationChannelController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ChannelView> list() {
        return service.listChannels();
    }

    @PutMapping("/{id}")
    public ChannelView update(@PathVariable Long id, @RequestBody ChannelRequest req) {
        return service.updateChannel(id, req);
    }
}
