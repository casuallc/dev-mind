package com.devmind.integration.service;

import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.project.RequirementExternalRefLookup;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RequirementExternalRefLookup 的 integration 侧实现：批量反查 external_links
 * （REQUIREMENT ↔ ISSUE），一次查询组装，避免需求列表 N+1。
 * 同一需求理论上只有一条 ISSUE link（同步幂等保证），多条时取首条。
 */
@Component
public class JiraRequirementRefLookup implements RequirementExternalRefLookup {

    private final ExternalLinkRepository linkRepo;

    public JiraRequirementRefLookup(ExternalLinkRepository linkRepo) {
        this.linkRepo = linkRepo;
    }

    @Override
    public Map<String, ExternalRef> refsFor(Collection<String> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return Map.of();
        }
        List<ExternalLinkEntity> links = linkRepo.findByInternalTypeAndInternalIdIn(
                ExternalLinkEntity.INTERNAL_REQUIREMENT, List.copyOf(requirementIds));
        Map<String, ExternalRef> out = new HashMap<>();
        for (ExternalLinkEntity link : links) {
            if (!ExternalLinkEntity.EXTERNAL_ISSUE.equals(link.getExternalType())) {
                continue;
            }
            out.putIfAbsent(link.getInternalId(),
                    new ExternalRef(link.getExternalUrl(), link.getStatus()));
        }
        return out;
    }
}
