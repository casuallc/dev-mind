package com.devmind.security.controller;

import com.devmind.security.service.SecurityToolcheckService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 节点工具自检（CAP-66 FR-12）：逐引擎 --version 轻量步骤下发目标节点。
 * ADMIN 门禁在 SecurityConfig（hasRole("ADMIN")）。
 */
@RestController
@RequestMapping("/api/security/nodes")
public class SecurityToolcheckController {

    private final SecurityToolcheckService toolcheckService;

    public SecurityToolcheckController(SecurityToolcheckService toolcheckService) {
        this.toolcheckService = toolcheckService;
    }

    @PostMapping("/{id}/toolcheck")
    public List<Map<String, Object>> toolcheck(@PathVariable String id) {
        return toolcheckService.checkAsMaps(id);
    }
}
