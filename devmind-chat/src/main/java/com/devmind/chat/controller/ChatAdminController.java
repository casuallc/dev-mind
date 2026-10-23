package com.devmind.chat.controller;

import com.devmind.chat.dto.NegativeFeedbackReportView;
import com.devmind.chat.service.KnowledgeFeedbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-84 FR-25 管理端：负面反馈按文档聚合（安全规则限定 ADMIN）。
 */
@RestController
@RequestMapping("/api/chats/admin")
public class ChatAdminController {

    private final KnowledgeFeedbackService feedbackService;

    public ChatAdminController(KnowledgeFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping("/knowledge-feedback/top")
    public NegativeFeedbackReportView topNegativeFeedback(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return feedbackService.topNegative(days, limit);
    }
}
