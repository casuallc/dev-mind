package com.devmind.security.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.security.model.SecuritySuppressionEntity;
import com.devmind.security.repo.SecuritySuppressionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 抑制与误报管理（CAP-66 FR-05）：规则 = 项目+仓库+引擎+fingerprint。
 * 过期（expiresAt 已过）即失效——匹配层忽略，历史 finding 状态不回访；
 * 解除/过期后下一次扫描同 fingerprint 重新按 NEW 出现。
 */
@Service
public class SuppressionService {

    public static final String TYPE_FALSE_POSITIVE = "FALSE_POSITIVE";
    public static final String TYPE_ACCEPTED_RISK = "ACCEPTED_RISK";
    public static final String TYPE_THIRD_PARTY = "THIRD_PARTY";

    private static final Set<String> TYPES = Set.of(TYPE_FALSE_POSITIVE, TYPE_ACCEPTED_RISK, TYPE_THIRD_PARTY);

    private final SecuritySuppressionRepository repo;

    public SuppressionService(SecuritySuppressionRepository repo) {
        this.repo = repo;
    }

    public SecuritySuppressionEntity requireById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "抑制规则不存在: " + id));
    }

    public SecuritySuppressionEntity create(String projectId, Long repoId, String engine,
                                            String fingerprint, String type, String reason,
                                            Instant expiresAt, String actor) {
        if (!TYPES.contains(type)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "抑制类型非法（可选 FALSE_POSITIVE/ACCEPTED_RISK/THIRD_PARTY）: " + type);
        }
        if (reason == null || reason.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "抑制理由必填");
        }
        SecuritySuppressionEntity existing = repo
                .findByProjectIdAndRepoIdAndEngineAndFingerprint(projectId, repoId, engine, fingerprint)
                .orElse(null);
        if (existing != null) {
            // 同规则重复创建 = 更新（类型/理由/过期重述），不报错不堆重复行
            existing.setType(type);
            existing.setReason(reason);
            existing.setExpiresAt(expiresAt);
            existing.setCreatedBy(actor);
            return repo.save(existing);
        }
        SecuritySuppressionEntity e = new SecuritySuppressionEntity();
        e.setProjectId(projectId);
        e.setRepoId(repoId);
        e.setEngine(engine);
        e.setFingerprint(fingerprint);
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

    public List<SecuritySuppressionEntity> list(String projectId) {
        return projectId == null || projectId.isBlank() ? repo.findAll() : repo.findByProjectId(projectId);
    }

    /** 项目+仓库+引擎维度的生效 fingerprint 集（过期剔除），归一化比对用 */
    public Set<String> activeFingerprints(String projectId, Long repoId, String engine) {
        Instant now = Instant.now();
        return repo.findByProjectIdAndRepoId(projectId, repoId).stream()
                .filter(s -> engine.equals(s.getEngine()))
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(now))
                .map(SecuritySuppressionEntity::getFingerprint)
                .collect(Collectors.toSet());
    }

    /** 抑制 id → fingerprint 映射（详情页展示命中规则用） */
    public Map<String, SecuritySuppressionEntity> activeByFingerprint(String projectId, Long repoId, String engine) {
        Instant now = Instant.now();
        return repo.findByProjectIdAndRepoId(projectId, repoId).stream()
                .filter(s -> engine.equals(s.getEngine()))
                .filter(s -> s.getExpiresAt() == null || s.getExpiresAt().isAfter(now))
                .collect(Collectors.toMap(SecuritySuppressionEntity::getFingerprint, s -> s, (a, b) -> a));
    }
}
