package com.devmind.project.controller;

import com.devmind.project.RelationService;
import com.devmind.project.dto.RelationRequest;
import com.devmind.project.dto.RelationView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Relation REST API（CAP-13 研发主线）：通用横向关系边。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/relations")
public class RelationController {

    private final RelationService service;

    public RelationController(RelationService service) {
        this.service = service;
    }

    /** 项目内全部边，或带 fromType+fromId 查某端点涉及的边（双向） */
    @GetMapping
    public List<RelationView> list(@PathVariable String projectId,
                                   @RequestParam(required = false) String fromType,
                                   @RequestParam(required = false) String fromId) {
        return service.list(projectId, fromType, fromId);
    }

    @PostMapping
    public RelationView create(@PathVariable String projectId, @Valid @RequestBody RelationRequest req) {
        return service.create(projectId, req);
    }

    @DeleteMapping("/{relationId}")
    public void delete(@PathVariable String projectId, @PathVariable String relationId) {
        service.delete(projectId, relationId);
    }
}
