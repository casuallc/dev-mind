package com.devmind.security.controller;

import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.dto.CreateScanRequest;
import com.devmind.security.model.SecurityFindingEntity;
import com.devmind.security.model.SecurityScanEntity;
import com.devmind.security.model.SecurityScanReportEntity;
import com.devmind.security.service.ExportService;
import com.devmind.security.service.FindingService;
import com.devmind.security.service.SecurityAccessGuard;
import com.devmind.security.service.SecurityScanService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 扫描任务 REST（CAP-66 FR-01/04/06/07）：发起/列表/详情/取消/发现列表/导出。
 * 权限：发起/取消 = 项目写（CAP-62 探测），列表/详情/发现/导出 = 项目读。
 */
@RestController
@RequestMapping("/api/security/scans")
public class SecurityScanController {

    private final SecurityScanService scanService;
    private final FindingService findingService;
    private final ExportService exportService;
    private final SecurityAccessGuard accessGuard;
    private final IdentityService identityService;

    public SecurityScanController(SecurityScanService scanService,
                                  FindingService findingService,
                                  ExportService exportService,
                                  SecurityAccessGuard accessGuard,
                                  IdentityService identityService) {
        this.scanService = scanService;
        this.findingService = findingService;
        this.exportService = exportService;
        this.accessGuard = accessGuard;
        this.identityService = identityService;
    }

    /** 任务详情：实体 + 引擎子状态 + 报告附件清单 */
    public record ScanDetail(SecurityScanEntity scan,
                             Map<String, Map<String, Object>> engineStatus,
                             List<SecurityScanReportEntity> reports) {
    }

    @PostMapping
    public SecurityScanEntity create(@RequestParam String projectId, @RequestBody CreateScanRequest req) {
        String actor = identityService.currentActor();
        accessGuard.requireWrite(actor, projectId);
        return scanService.create(projectId, req.repoId(), req.branch(), req.engines(), req.agentNodeId(), actor);
    }

    @GetMapping
    public Page<SecurityScanEntity> list(@RequestParam String projectId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        accessGuard.requireRead(identityService.currentActor(), projectId);
        return scanService.list(projectId, page, size);
    }

    @GetMapping("/{id}")
    public ScanDetail detail(@PathVariable long id) {
        SecurityScanEntity scan = scanService.get(id);
        accessGuard.requireRead(identityService.currentActor(), scan.getProjectId());
        return new ScanDetail(scan, scanService.engineStatus(scan), scanService.reports(id));
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable long id) {
        SecurityScanEntity scan = scanService.get(id);
        accessGuard.requireWrite(identityService.currentActor(), scan.getProjectId());
        scanService.cancel(id);
    }

    /**
     * 发现列表（FR-06）：默认 status=NEW + 排除 test scope；status=ALL 不过滤。
     * kev/epssMin/kevFirst 走 detail_json 惰性解析（FR-10-4 情报排序）。
     */
    @GetMapping("/{id}/findings")
    public List<SecurityFindingEntity> findings(@PathVariable long id,
                                                @RequestParam(required = false) String engine,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String severity,
                                                @RequestParam(required = false) Boolean directDep,
                                                @RequestParam(required = false) Boolean kev,
                                                @RequestParam(required = false) Double epssMin,
                                                @RequestParam(defaultValue = "false") boolean includeTestScope,
                                                @RequestParam(defaultValue = "false") boolean kevFirst) {
        SecurityScanEntity scan = scanService.get(id);
        accessGuard.requireRead(identityService.currentActor(), scan.getProjectId());
        String effectiveStatus = status == null || status.isBlank()
                ? SecurityFindingEntity.NEW
                : ("ALL".equalsIgnoreCase(status) ? null : status);
        return findingService.query(id, engine, effectiveStatus, severity, directDep, kev, epssMin,
                includeTestScope, kevFirst);
    }

    /** 导出（FR-07）：csv / sarif，产物落附件（SHARED），返回附件 id */
    @GetMapping("/{id}/export")
    public ExportService.ExportResult export(@PathVariable long id, @RequestParam String format) {
        SecurityScanEntity scan = scanService.get(id);
        String actor = identityService.currentActor();
        accessGuard.requireRead(actor, scan.getProjectId());
        return switch (format == null ? "" : format.toLowerCase()) {
            case "csv" -> exportService.exportCsv(scan, actor);
            case "sarif" -> exportService.exportSarif(scan, actor);
            default -> throw new DevMindException(ErrorCode.BAD_REQUEST, "format 仅支持 csv / sarif");
        };
    }
}
