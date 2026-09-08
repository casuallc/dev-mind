package com.devmind.session.controller;

import com.devmind.session.dto.ScenarioPreviewView;
import com.devmind.session.dto.ScenarioRequest;
import com.devmind.session.dto.ScenarioView;
import com.devmind.session.service.ScenarioPreviewService;
import com.devmind.session.service.ScenarioService;
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

import java.util.List;

/**
 * CAP-33 FR-01/FR-06 场景 REST API（session_templates 的升级形态，/api/templates 已随
 * SessionTemplateController 删除）。权限走 SecurityConfig 兜底：GET 认证、写 ADMIN|DEVELOPER。
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final ScenarioPreviewService previewService;

    public ScenarioController(ScenarioService scenarioService, ScenarioPreviewService previewService) {
        this.scenarioService = scenarioService;
        this.previewService = previewService;
    }

    /** enabled=true 仅出启用项（创建表单下拉）；缺省全量（管理页）。 */
    @GetMapping
    public List<ScenarioView> list(@RequestParam(required = false) Boolean enabled) {
        return Boolean.TRUE.equals(enabled) ? scenarioService.listEnabled() : scenarioService.list();
    }

    @PostMapping
    public ScenarioView create(@Valid @RequestBody ScenarioRequest req) {
        return scenarioService.save(null, req);
    }

    @PutMapping("/{id}")
    public ScenarioView update(@PathVariable Long id, @Valid @RequestBody ScenarioRequest req) {
        return scenarioService.save(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        scenarioService.delete(id);
    }

    /** 骨架渲染 + dryRun 装配预览（不 bumpHits，无副作用）。 */
    @GetMapping("/{code}/preview")
    public ScenarioPreviewView preview(@PathVariable String code,
                                       @RequestParam(required = false) String projectId,
                                       @RequestParam(required = false) String taskSpec) {
        return previewService.preview(code, projectId, taskSpec);
    }
}
