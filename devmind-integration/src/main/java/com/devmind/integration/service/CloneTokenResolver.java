package com.devmind.integration.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.repo.IntegrationRepository;
import org.springframework.stereotype.Service;

/**
 * CAP-29 抽出的共享克隆/抓取凭据解析（原 RepoCloneService.resolveToken 的通用化）：
 * 校验集成存在、ENABLED、类型 GITLAB/GITHUB、base_url host 与 remoteUrl host 一致
 * （防拿 A 平台 token 撞 B 平台）。返回 null = 匿名。token 仅内存使用，不进日志。
 */
@Service
public class CloneTokenResolver {

    private final IntegrationRepository integrationRepo;
    private final IntegrationService integrationService;

    public CloneTokenResolver(IntegrationRepository integrationRepo, IntegrationService integrationService) {
        this.integrationRepo = integrationRepo;
        this.integrationService = integrationService;
    }

    /** 解析凭据；integrationId 空 = 匿名（file:// 只允许匿名）。 */
    public String resolve(Long integrationId, String remoteUrl) {
        if (integrationId == null) {
            return null;
        }
        if (remoteUrl != null && remoteUrl.trim().startsWith("file://")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "file:// 仅支持匿名克隆（不可选择集成实例）");
        }
        IntegrationEntity e = integrationRepo.findById(integrationId)
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "集成实例不存在: " + integrationId));
        if (!IntegrationEntity.STATUS_ENABLED.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "集成实例已禁用: " + e.getName());
        }
        if (!IntegrationEntity.TYPE_GITLAB.equals(e.getType()) && !IntegrationEntity.TYPE_GITHUB.equals(e.getType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "克隆仅支持 GitLab/GitHub 集成实例: " + e.getType());
        }
        String repoHost = hostOf(remoteUrl);
        String baseHost = hostOf(e.getBaseUrl());
        if (repoHost != null && baseHost != null && !repoHost.equalsIgnoreCase(baseHost)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "集成实例地址（" + baseHost + "）与仓库远端主机（" + repoHost + "）不一致");
        }
        return integrationService.tokenOf(e);
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return java.net.URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
