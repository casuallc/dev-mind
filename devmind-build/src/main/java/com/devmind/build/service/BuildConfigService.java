package com.devmind.build.service;

import com.devmind.build.config.BuildProperties;
import com.devmind.build.dto.BuildConfigRequest;
import com.devmind.build.dto.BuildConfigView;
import com.devmind.build.model.BuildConfigEntity;
import com.devmind.build.repo.BuildConfigRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * CAP-08 FR-02 构建配置：每项目一份（executor/远程服务器/并发上限），无记录时给默认值。
 */
@Service
public class BuildConfigService {

    private final BuildConfigRepository repo;
    private final BuildProperties props;

    public BuildConfigService(BuildConfigRepository repo, BuildProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /** 读取配置，缺失时返回默认视图（不落库，展示即可） */
    public BuildConfigView get(String projectId) {
        return toView(repo.findByProjectId(projectId).orElseGet(() -> {
            BuildConfigEntity d = new BuildConfigEntity();
            d.setProjectId(projectId);
            d.setExecutor("LOCAL");
            d.setConcurrencyLimit(props.getDefaultConcurrency());
            return d;
        }));
    }

    @Transactional
    public BuildConfigView update(String projectId, BuildConfigRequest req) {
        String executor = req.executor() == null || req.executor().isBlank() ? "LOCAL" : req.executor().trim().toUpperCase();
        if (!"LOCAL".equals(executor) && !"REMOTE".equals(executor)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "executor 只能是 LOCAL 或 REMOTE");
        }
        if ("REMOTE".equals(executor) && (req.remoteServerId() == null || req.remoteServerId() <= 0)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "远程执行需指定目标服务器");
        }
        int limit = req.concurrencyLimit() != null && req.concurrencyLimit() > 0 ? req.concurrencyLimit() : props.getDefaultConcurrency();
        BuildConfigEntity e = repo.findByProjectId(projectId).orElseGet(() -> {
            BuildConfigEntity n = new BuildConfigEntity();
            n.setProjectId(projectId);
            return n;
        });
        e.setExecutor(executor);
        e.setRemoteServerId(req.remoteServerId());
        e.setConcurrencyLimit(limit);
        e.setUpdatedAt(Instant.now());
        return toView(repo.save(e));
    }

    private BuildConfigView toView(BuildConfigEntity e) {
        return new BuildConfigView(e.getProjectId(), e.getExecutor(), e.getRemoteServerId(), e.getConcurrencyLimit());
    }
}
