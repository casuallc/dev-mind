package com.devmind.skill.controller;

import com.devmind.project.dto.PageView;
import com.devmind.skill.SkillService;
import com.devmind.skill.dto.SkillDetailView;
import com.devmind.skill.dto.SkillFileContentView;
import com.devmind.skill.dto.SkillFileRequest;
import com.devmind.skill.dto.SkillFileView;
import com.devmind.skill.dto.SkillPackageView;
import com.devmind.skill.dto.SkillRequest;
import com.devmind.skill.dto.SkillView;
import jakarta.validation.Valid;

import java.util.List;
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

    // ---------------- 导出（为注入预留） ----------------

    /** 按 ids 导出 skill 包文件树（files[0] 固定为拼好 frontmatter 的 SKILL.md）。 */
    @GetMapping("/export")
    public SkillPackageView exportPackages(@RequestParam List<String> ids) {
        return service.exportPackages(ids);
    }

    // ---------------- 附件文件 ----------------

    @GetMapping("/{id}/files")
    public List<SkillFileView> listFiles(@PathVariable String id) {
        return service.listFiles(id);
    }

    @GetMapping("/{id}/files/{fileId}")
    public SkillFileContentView getFileContent(@PathVariable String id, @PathVariable String fileId) {
        return service.getFileContent(id, fileId);
    }

    @PostMapping("/{id}/files")
    public SkillFileView addFile(@PathVariable String id, @RequestBody SkillFileRequest req) {
        return service.addFile(id, req);
    }

    @PutMapping("/{id}/files/{fileId}")
    public SkillFileView updateFile(@PathVariable String id, @PathVariable String fileId,
                                    @RequestBody SkillFileRequest req) {
        return service.updateFile(id, fileId, req);
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public void deleteFile(@PathVariable String id, @PathVariable String fileId) {
        service.deleteFile(id, fileId);
    }
}
