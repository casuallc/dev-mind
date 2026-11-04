package com.devmind.security.service;

import com.devmind.security.config.SecurityProperties;
import com.devmind.security.model.SecurityProjectConfigEntity;
import com.devmind.security.repo.SecurityProjectConfigRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 项目级扫描配置（CAP-66 FR-10-1）：内部构件白名单的项目追加部分，
 * 与平台默认（devmind.security.internal-group-prefixes）合并生效。
 */
@Service
public class SecurityProjectConfigService {

    private final SecurityProjectConfigRepository repo;
    private final SecurityProperties props;

    public SecurityProjectConfigService(SecurityProjectConfigRepository repo, SecurityProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /** 合并后的内部 groupId 前缀（平台 + 项目追加，去重去空） */
    public List<String> mergedInternalPrefixes(String projectId) {
        Set<String> merged = new LinkedHashSet<>();
        for (String p : props.getInternalGroupPrefixes()) {
            addCsv(merged, p);
        }
        SecurityProjectConfigEntity cfg = repo.findById(projectId).orElse(null);
        if (cfg != null && cfg.getInternalGroupPrefixes() != null) {
            addCsv(merged, cfg.getInternalGroupPrefixes());
        }
        return List.copyOf(merged);
    }

    public SecurityProjectConfigEntity get(String projectId) {
        return repo.findById(projectId).orElse(null);
    }

    public SecurityProjectConfigEntity put(String projectId, String internalGroupPrefixes) {
        SecurityProjectConfigEntity cfg = repo.findById(projectId).orElseGet(SecurityProjectConfigEntity::new);
        cfg.setProjectId(projectId);
        cfg.setInternalGroupPrefixes(internalGroupPrefixes);
        cfg.setUpdatedAt(Instant.now());
        return repo.save(cfg);
    }

    private static void addCsv(Set<String> out, String csv) {
        if (csv == null) {
            return;
        }
        for (String p : csv.split(",")) {
            String v = p.strip();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
    }

    /** groupId 是否命中白名单前缀 */
    public static boolean matchesPrefix(List<String> prefixes, String groupId) {
        if (groupId == null) {
            return false;
        }
        for (String p : prefixes) {
            // 严格段边界：com.apusic 命中 com.apusic.xxx，不误伤 com.apusic2
            if (groupId.equals(p) || groupId.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }
}
