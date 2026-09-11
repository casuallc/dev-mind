package com.devmind.execution.audit;

import com.devmind.common.audit.AuditLogEntity;
import com.devmind.common.audit.AuditLogRepository;
import com.devmind.common.dto.PageView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行审计查询（原 CAP-07 FR-06，CAP-36 起由执行底座承接；路由保持不变）。
 * 服务端真分页：page 从 0 起，size 限制 [1, 200]（与部署历史/需求列表同一约定）。
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogRepository repo;

    public AuditLogController(AuditLogRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public PageView<AuditView> list(@RequestParam(required = false) String projectId,
                                    @RequestParam(required = false) Long serverId,
                                    @RequestParam(required = false) String action,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);
        Page<AuditLogEntity> result = repo.search(serverId, blankToNull(projectId), blankToNull(action),
                PageRequest.of(p, s));
        return new PageView<>(result.getContent().stream().map(this::toView).toList(),
                result.getTotalElements(), p, s);
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private AuditView toView(AuditLogEntity a) {
        return new AuditView(a.getId(), a.getProjectId(), a.getServerId(), a.getServerName(), a.getAccessType(),
                a.getAction(), a.getTemplateCode(), a.getCapability(), a.getCommand(), a.getExitCode(),
                Boolean.TRUE.equals(a.getSuccess()), a.getDetail(), a.getDurationMs(), a.getCreatedAt());
    }
}
