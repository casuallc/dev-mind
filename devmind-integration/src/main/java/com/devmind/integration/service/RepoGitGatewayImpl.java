package com.devmind.integration.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.repo.ProjectRepoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * CAP-25/26 {@link RepoGitGateway} 实现：凭据解析委托 {@link IntegrationService#resolveGitToken}
 * （个人 PAT → 项目绑定 Integration），fetch 走 {@link GitRemoteOps}（token 仅进程参数注入）。
 */
@Component
public class RepoGitGatewayImpl implements RepoGitGateway {

    private static final Logger log = LoggerFactory.getLogger(RepoGitGatewayImpl.class);

    private final ProjectRepoRepository projectRepoRepo;
    private final IntegrationService integrationService;
    private final GitRemoteOps gitOps;

    public RepoGitGatewayImpl(ProjectRepoRepository projectRepoRepo,
                              IntegrationService integrationService,
                              GitRemoteOps gitOps) {
        this.projectRepoRepo = projectRepoRepo;
        this.integrationService = integrationService;
        this.gitOps = gitOps;
    }

    @Override
    public Optional<String> resolveToken(String actor, String repoHost, String projectId) {
        return integrationService.resolveGitToken(actor, repoHost, projectId);
    }

    @Override
    public boolean fetch(String repoPath, String ref, String actor) {
        ProjectRepoEntity repo = findByPath(repoPath);
        String remoteUrl = repo != null ? repo.getRemoteUrl() : null;
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return false; // 纯本地库：未执行，调用方走本地基准
        }
        String host = UserGitCredentialService.hostOf(remoteUrl);
        String token = integrationService.resolveGitToken(actor, host, repo.getProjectId()).orElse(null);
        GitRemoteOps.GitResult r = gitOps.fetch(repoPath, ref, remoteUrl, token);
        if (!r.ok()) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "git fetch 失败（" + host + "）: " + tail(r.output()));
        }
        log.info("执行前 fetch 完成: repo={} ref={} actor={}", repoPath, ref, actor);
        return true;
    }

    /** 按 normalize 后路径匹配登记的仓库（存储值与调用方可能分隔符/大小写风格不同） */
    private ProjectRepoEntity findByPath(String repoPath) {
        if (repoPath == null || repoPath.isBlank()) {
            return null;
        }
        String target = normalize(repoPath);
        return projectRepoRepo.findAll().stream()
                .filter(e -> normalize(e.getPath()).equalsIgnoreCase(target))
                .findFirst().orElse(null);
    }

    private static String normalize(String p) {
        try {
            return Path.of(p).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return p;
        }
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= 300 ? t : "…" + t.substring(t.length() - 300);
    }
}
