package com.devmind.onboarding;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-20 AI 项目接入入口（仅 ADMIN，SecurityConfig 规则）。
 */
@RestController
@RequestMapping("/api/projects")
public class OnboardingController {

    private final OnboardingService service;

    public OnboardingController(OnboardingService service) {
        this.service = service;
    }

    @PostMapping("/onboard")
    public OnboardResponse onboard(@Valid @RequestBody OnboardRequest req) {
        return new OnboardResponse(service.start(req.description()));
    }

    public record OnboardRequest(@NotBlank(message = "description 不能为空") String description) {
    }

    public record OnboardResponse(String sessionId) {
    }
}
