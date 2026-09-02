package com.devmind.skill.controller;

import com.devmind.project.dto.PageView;
import com.devmind.skill.SkillService;
import com.devmind.skill.dto.SkillDetailView;
import com.devmind.skill.dto.SkillRequest;
import com.devmind.skill.dto.SkillView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skill 管理 REST（基础模块）：skill 包本体 CRUD/启停/分页检索 + 附件管理 + 导出。
 * 平铺 URL 风格对照 KnowledgeController，scope/projectId 走 query param。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService service;

    public SkillController(SkillService service) {
        this.service = service;
    }

    // ---------------- 本体 ----------------

    @GetMapping
    public PageView<SkillView> list(@RequestParam(required = false) String scope,
                                    @RequestParam(required = false) String projectId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.list(scope, projectId, status, keyword, page, size);
    }

    @GetMapping("/{id}")
    public SkillDetailView get(@PathVariable String id) {
        return service.getDetail(id);
    }

    @PostMapping
    public SkillView create(@Valid @RequestBody SkillRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public SkillView update(@PathVariable String id, @Valid @RequestBody SkillRequest req) {
        return service.update(id, req);
    }

    @PutMapping("/{id}/status")
    public SkillView updateStatus(@PathVariable String id, @RequestParam String status) {
        return service.updateStatus(id, status);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
