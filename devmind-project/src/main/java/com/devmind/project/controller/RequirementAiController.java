package com.devmind.project.controller;

import com.devmind.project.RequirementAiExpandService;
import com.devmind.project.dto.AiExpandRequest;
import com.devmind.project.dto.AiExpandResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-105 需求 AI 扩充：纯生成接口（无任何写库副作用），结果由前端回填描述字段，
 * 用户确认后经既有 create/update 落库。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements")
public class RequirementAiController {

    private final RequirementAiExpandService service;

    public RequirementAiController(RequirementAiExpandService service) {
        this.service = service;
    }

    @PostMapping("/ai-expand")
    public AiExpandResponse aiExpand(@PathVariable String projectId,
                                     @Valid @RequestBody AiExpandRequest req) {
        return service.expand(req);
    }
}
