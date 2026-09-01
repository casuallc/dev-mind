package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.dto.JiraSyncConfigRequest;
import com.devmind.integration.dto.JiraSyncConfigView;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.model.JiraSyncConfigEntity;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.JiraSyncConfigRepository;
import com.devmind.project.ProjectService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * CAP-19 Jira 同步：配置 CRUD + 轮询/手动同步执行（单向只拉取，不回写 Jira）。
 * issue → Requirement（DRAFT）经 RequirementService 落主线，external_links 登记幂等。
 */
@Service
public class JiraSyncService {

    private final JiraSyncConfigRepository configRepo;
    private final IntegrationRepository integrationRepo;
    private final IntegrationService integrationService;
    private final ProjectService projectService;
    private final IdentityService identityService;
    private final AuditService auditService;

    public JiraSyncService(JiraSyncConfigRepository configRepo,
                           IntegrationRepository integrationRepo,
                           IntegrationService integrationService,
                           ProjectService projectService,
                           IdentityService identityService,
                           AuditService auditService) {
        this.configRepo = configRepo;
        this.integrationRepo = integrationRepo;
        this.integrationService = integrationService;
        this.projectService = projectService;
        this.identityService = identityService;
        this.auditService = auditService;
    }

    // ---------------- 配置 CRUD ----------------

    public List<JiraSyncConfigView> list(String projectId) {
        projectService.requireProject(projectId);
        return configRepo.findByProjectIdOrderByIdAsc(projectId).stream().map(this::toView).toList();
    }

    public JiraSyncConfigView create(String projectId, JiraSyncConfigRequest req) {
        projectService.requireProject(projectId);
        if (req.integrationId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "integrationId 不能为空");
        }
        IntegrationEntity integration = integrationService.require(req.integrationId());
        if (!IntegrationEntity.TYPE_JIRA.equals(integration.getType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "集成 #" + req.integrationId() + " 不是 JIRA 类型（" + integration.getType() + "）");
        }
        if (req.jiraProjectKey() == null || req.jiraProjectKey().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "jiraProjectKey 不能为空（Jira 项目 key，如 PROJ）");
        }
        configRepo.findByIntegrationIdAndProjectId(req.integrationId(), projectId).ifPresent(x -> {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "项目已配置该 Jira 实例的同步（配置 #" + x.getId() + "），请直接修改");
        });
        JiraSyncConfigEntity e = new JiraSyncConfigEntity();
        e.setIntegrationId(req.integrationId());
        e.setProjectId(projectId);
        e.setJiraProjectKey(req.jiraProjectKey().trim().toUpperCase());
        e.setJql(blankToNull(req.jql()));
        e.setEnabled(req.enabled() == null || req.enabled());
        e.setPollIntervalSec(normalizeInterval(req.pollIntervalSec()));
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        JiraSyncConfigEntity saved = configRepo.save(e);
        audit("jira_sync_config_create", saved.getIntegrationId(), projectId, true,
                "Jira 同步配置 " + saved.getJiraProjectKey() + " → 项目 " + projectId);
        return toView(saved);
    }

    public JiraSyncConfigView update(String projectId, Long configId, JiraSyncConfigRequest req) {
        JiraSyncConfigEntity e = require(projectId, configId);
        if (req.jiraProjectKey() != null && !req.jiraProjectKey().isBlank()) {
            String key = req.jiraProjectKey().trim().toUpperCase();
            if (!key.equals(e.getJiraProjectKey())) {
                e.setJiraProjectKey(key);
                // 换项目 = 同步范围变化，水位线清零重拉（幂等由 external_links 兜住）
                e.setLastWatermark(null);
            }
        }
        if (req.jql() != null) {
            e.setJql(blankToNull(req.jql()));
        }
        if (req.enabled() != null) {
            e.setEnabled(req.enabled());
        }
        if (req.pollIntervalSec() != null) {
            e.setPollIntervalSec(normalizeInterval(req.pollIntervalSec()));
        }
        e.setUpdatedAt(Instant.now());
        return toView(configRepo.save(e));
    }

    public void delete(String projectId, Long configId) {
        JiraSyncConfigEntity e = require(projectId, configId);
        configRepo.delete(e);
        audit("jira_sync_config_delete", e.getIntegrationId(), projectId, true,
                "删除 Jira 同步配置 #" + configId + "（" + e.getJiraProjectKey() + "）");
    }

    public JiraSyncConfigEntity require(String projectId, Long configId) {
        return configRepo.findById(configId)
                .filter(x -> projectId.equals(x.getProjectId()))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "Jira 同步配置不存在: " + configId));
    }

    // ---------------- 内部 ----------------

    private int normalizeInterval(Integer sec) {
        int v = sec == null ? 300 : sec;
        if (v < 60) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "pollIntervalSec 最小 60 秒");
        }
        return v;
    }

    private void audit(String action, Long integrationId, String projectId, boolean success, String detail) {
        auditService.record("integration", action, identityService.currentActor(),
                projectId, success, integrationId != null ? "[#" + integrationId + "] " + detail : detail);
    }

    private JiraSyncConfigView toView(JiraSyncConfigEntity e) {
        IntegrationEntity integration = integrationRepo.findById(e.getIntegrationId()).orElse(null);
        return new JiraSyncConfigView(e.getId(), e.getIntegrationId(),
                integration != null ? integration.getName() : null,
                e.getProjectId(), e.getJiraProjectKey(), e.getJql(), e.isEnabled(),
                e.getPollIntervalSec(), e.getLastSyncAt(), e.getLastWatermark(),
                e.getLastImported(), e.getLastUpdatedCount(), e.getLastError(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
