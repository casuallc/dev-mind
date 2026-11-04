package com.devmind.security.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.model.SecurityCveSuppressionEntity;
import com.devmind.security.repo.SecurityCveSuppressionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * CVE 抑制库（CAP-66 FR-10-3）：钉「构件 × 漏洞」，与 fingerprint 抑制（FR-05）双轨互补。
 * artifactId='*' = 该 group 整组；projectId=null = 平台级全局生效（仅 ADMIN 可建删，控制器判定）；
 * ACCEPTED_RISK 强制 expires_at。一处裁定、按 scope 复用到所有扫描。
 *
 * <p>注意：uk 含可空列 project_id，H2/MySQL 视 NULL 互不等值，平台级条目撞不了 uk，
 * save 前必须应用层查重（group+artifact+cve+projectId 四元组）。</p>
 */
@Service
public class CveSuppressionService {

    public static final String TYPE_FALSE_POSITIVE = "FALSE_POSITIVE";
    public static final String TYPE_ACCEPTED_RISK = "ACCEPTED_RISK";

    private final SecurityCveSuppressionRepository repo;

    public CveSuppressionService(SecurityCveSuppressionRepository repo) {
        this.repo = repo;
    }

    public SecurityCveSuppressionEntity requireById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "CVE 抑制不存在: " + id));
    }

    public SecurityCveSuppressionEntity create(String groupId, String artifactId, String cve,
                                               String projectId, String type, String reason,
                                               Instant expiresAt, String actor) {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()
                || cve == null || cve.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "groupId/artifactId/cve 均必填（artifactId 可用 * 表整组）");
        }
        if (!TYPE_FALSE_POSITIVE.equals(type) && !TYPE_ACCEPTED_RISK.equals(type)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "CVE 抑制类型非法（可选 FALSE_POSITIVE/ACCEPTED_RISK）: " + type);
        }
        if (reason == null || reason.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "抑制理由必填");
        }
        if (TYPE_ACCEPTED_RISK.equals(type) && expiresAt == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "ACCEPTED_RISK 类型必须设置过期时间");
        }
        // 应用层查重（uk 含可空 project_id，NULL 撞不上唯一约束）
        boolean dup = repo.findByGroupIdAndArtifactIdAndCve(groupId, artifactId, cve).stream()
                .anyMatch(e -> projectId == null ? e.getProjectId() == null : projectId.equals(e.getProjectId()));
        if (dup) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "该 构件×CVE" + (projectId == null ? "（平台级）" : "（本项目）") + " 抑制已存在");
        }
        SecurityCveSuppressionEntity e = new SecurityCveSuppressionEntity();
        e.setGroupId(groupId.strip());
        e.setArtifactId(artifactId.strip());
        e.setCve(cve.strip());
        e.setProjectId(projectId);
        e.setType(type);
        e.setReason(reason);
        e.setExpiresAt(expiresAt);
        e.setCreatedBy(actor);
        e.setCreatedAt(Instant.now());
        return repo.save(e);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    public List<SecurityCveSuppressionEntity> list(String projectId, String cve) {
        List<SecurityCveSuppressionEntity> all = projectId == null || projectId.isBlank()
                ? repo.findAll() : repo.findEffectiveForProject(projectId);
        if (cve == null || cve.isBlank()) {
            return all;
        }
        return all.stream().filter(e -> cve.equalsIgnoreCase(e.getCve())).toList();
    }

    /** 归一化匹配：项目生效集（项目级+平台级）内，未过期且 构件×CVE 命中（artifactId 支持 * 整组） */
    public SecurityCveSuppressionEntity match(String projectId, String groupId, String artifactId, String cve,
                                              List<SecurityCveSuppressionEntity> effective) {
        if (groupId == null || cve == null) {
            return null;
        }
        Instant now = Instant.now();
        for (SecurityCveSuppressionEntity e : effective) {
            if (e.getExpiresAt() != null && !e.getExpiresAt().isAfter(now)) {
                continue;
            }
            if (!groupId.equals(e.getGroupId()) || !cve.equals(e.getCve())) {
                continue;
            }
            if ("*".equals(e.getArtifactId()) || e.getArtifactId().equals(artifactId)) {
                return e;
            }
        }
        return null;
    }

    /** 项目生效集（项目级 + 平台级），归一化批取一次 */
    public List<SecurityCveSuppressionEntity> effectiveFor(String projectId) {
        return repo.findEffectiveForProject(projectId);
    }
}
