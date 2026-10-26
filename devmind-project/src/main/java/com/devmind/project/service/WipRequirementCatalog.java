package com.devmind.project.service;

import com.devmind.common.requirement.WipRequirementProvider;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.RequirementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-88 FR-10 {@link WipRequirementProvider} 实现：以 CAP-13 研发主线 Requirement
 * 为源（owner 或 assignee 命中、进行中/待验收），供「AI 建议计划」等组装层取在办素材。
 * 查询异常吞掉返回空表（SPI 契约：缺席/失败降级，不阻断消费方）。
 */
@Component
public class WipRequirementCatalog implements WipRequirementProvider {

    private static final Logger log = LoggerFactory.getLogger(WipRequirementCatalog.class);

    private final RequirementRepository requirementRepo;
    private final ProjectRepository projectRepo;

    public WipRequirementCatalog(RequirementRepository requirementRepo, ProjectRepository projectRepo) {
        this.requirementRepo = requirementRepo;
        this.projectRepo = projectRepo;
    }

    @Override
    public List<WipRequirement> findWip(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            List<RequirementEntity> rows = requirementRepo.findWipByUser(userId, PageRequest.of(0, limit));
            if (rows.isEmpty()) {
                return List.of();
            }
            // 项目名批量取（防 N+1）；项目缺失时名字留空不阻断（[[map-of-null-key-npe]]：get 前判空）
            List<String> projectIds = rows.stream().map(RequirementEntity::getProjectId).distinct().toList();
            Map<String, String> projectNames = new HashMap<>();
            projectRepo.findAllById(projectIds).forEach(p -> projectNames.put(p.getId(), p.getName()));
            return rows.stream()
                    .map(r -> new WipRequirement(
                            r.getExternalKey() != null && !r.getExternalKey().isBlank() ? r.getExternalKey() : r.getId(),
                            r.getTitle(), r.getStatus(), projectNames.get(r.getProjectId())))
                    .toList();
        } catch (Exception e) {
            log.warn("查询在办需求失败（降级为空素材）: userId={} err={}", userId, e.getMessage());
            return List.of();
        }
    }
}
