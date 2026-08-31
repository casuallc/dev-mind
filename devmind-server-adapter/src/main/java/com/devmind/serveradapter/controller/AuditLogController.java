package com.devmind.serveradapter.controller;

import com.devmind.serveradapter.dto.AuditView;
import com.devmind.serveradapter.model.AuditLogEntity;
import com.devmind.serveradapter.repo.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-07 FR-06 执行审计查询。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogRepository repo;

    public AuditLogController(AuditLogRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<AuditView> list(@RequestParam(required = false) String projectId,
                                @RequestParam(required = false) Long serverId,
                                @RequestParam(required = false) String action,
                                @RequestParam(defaultValue = "100") int limit) {
        List<AuditLogEntity> rows;
        PageRequest page = PageRequest.of(0, Math.min(limit, 300), Sort.by(Sort.Direction.DESC, "id"));
        if (serverId != null) {
            rows = repo.findByServerIdOrderByIdDesc(serverId, page);
        } else if (projectId != null && !projectId.isBlank()) {
            rows = repo.findByProjectIdOrderByIdDesc(projectId, page);
        } else {
            rows = repo.findAll(page).toList();
        }
        if (action != null && !action.isBlank()) {
            String a = action;
            rows = rows.stream().filter(r -> a.equals(r.getAction())).toList();
        }
        return rows.stream().map(this::toView).toList();
    }

    private AuditView toView(AuditLogEntity a) {
        return new AuditView(a.getId(), a.getProjectId(), a.getServerId(), a.getServerName(), a.getAccessType(),
                a.getAction(), a.getTemplateCode(), a.getCapability(), a.getCommand(), a.getExitCode(),
                Boolean.TRUE.equals(a.getSuccess()), a.getDetail(), a.getDurationMs(), a.getCreatedAt());
    }
}
