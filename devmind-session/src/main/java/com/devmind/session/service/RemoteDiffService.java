package com.devmind.session.service;

import com.devmind.common.integration.RepoGitGateway;
import com.devmind.common.util.GitCli;
import com.devmind.project.GitRepoService;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import com.devmind.session.dto.RepoDiffView;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionRepoEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAP-31 远程会话 diff：工作区在 runner 节点侧，结束即 push 分支并清理，本地无 worktree 可看。
 * 取数走 CAP-29 服务端克隆缓存（{@link GitRepoService#deriveClonePath} 确定性路径）：
 * fetch 会话分支与基线 → {@code git diff base...branch --stat/--name-only}。
 *
 * <p>凭据红线（同 CAP-25）：token 仅存内存、URL 内嵌注入（oauth2 用户名约定）、
 * 所有 git 输出经脱敏（明文 + URL 编码形态 → ***）后才进错误消息/日志，绝不落库。</p>
 *
 * <p>单库失败只填该行 error，不拖垮整组；按规范化 URL 加锁防并发 fetch 互踩缓存。</p>
 */
@Service
public class RemoteDiffService {

    private static final Logger log = LoggerFactory.getLogger(RemoteDiffService.class);
    /** 拉取分支在缓存中的落地 ref 前缀（避开 origin/* 命名空间，防与常规 fetch 互相覆盖） */
    private static final String REF_PREFIX = "refs/remotes/devmind/";

    private final GitRepoService gitRepoService;
    private final ProjectService projectService;
    private final ObjectProvider<RepoGitGateway> repoGitGateway;
    /** 同一远端 URL 的 fetch 串行化（共享克隆缓存，并发 fetch 会互锁 .git） */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public RemoteDiffService(GitRepoService gitRepoService,
                             ProjectService projectService,
                             ObjectProvider<RepoGitGateway> repoGitGateway) {
        this.gitRepoService = gitRepoService;
        this.projectService = projectService;
        this.repoGitGateway = repoGitGateway;
    }

    /**
     * 远程会话按快照逐库 diff。rows 为空（旧路径：项目无仓库行）时按项目主库镜像列单库兜底；
     * 项目/主库都没有可用 http 远端时返回单行 error。
     */
    public List<RepoDiffView> diff(SessionEntity ent, List<SessionRepoEntity> rows) {
        if (rows.isEmpty()) {
            Project project = resolveProject(ent.getProjectId());
            if (project == null) {
                return List.of(RepoDiffView.error("主库", true, "会话无项目信息，远程 diff 不可用"));
            }
            String remoteUrl;
            try {
                remoteUrl = projectService.primaryRepo(project.id()).getRemoteUrl();
            } catch (Exception e) {
                return List.of(RepoDiffView.error(project.name(), true, "项目主库无远端地址: " + e.getMessage()));
            }
            return List.of(diffOne(ent, project.name(), true, remoteUrl,
                    ent.getBaseBranch(), branchOf(ent)));
        }
        List<RepoDiffView> out = new ArrayList<>();
        for (SessionRepoEntity row : rows) {
            out.add(diffOne(ent, row.getName(), Boolean.TRUE.equals(row.getIsPrimary()),
                    row.getRemoteUrl(), row.getBaseBranch(), row.getBranch()));
        }
        return out;
    }

    private RepoDiffView diffOne(SessionEntity ent, String name, boolean primary,
                                 String remoteUrl, String baseBranch, String branch) {
        String url = remoteUrl == null ? "" : remoteUrl.trim();
        if (url.isBlank() || url.startsWith("git@") || url.startsWith("ssh://")) {
            return RepoDiffView.error(name, primary, "远程 diff 仅支持 http(s) 远端（该库无可用 http 地址）");
        }
        if (baseBranch == null || baseBranch.isBlank() || branch == null || branch.isBlank()) {
            return RepoDiffView.error(name, primary, "缺少基线/会话分支信息，无法 diff");
        }
        Path cache = gitRepoService.deriveClonePath(url);
        if (!Files.isDirectory(cache.resolve(".git"))) {
            return RepoDiffView.error(name, primary, "服务端克隆未就绪（请先在「代码仓库」登记并完成克隆）");
        }
        String token = resolveToken(ent, url);
        try {
            return fetchAndDiff(name, primary, url, baseBranch, branch, cache, token);
        } catch (Exception e) {
            // git 超时/IO 等异常不拖垮整组（异常消息理论上不含 URL，仍过一道脱敏兜底）
            return RepoDiffView.error(name, primary, "diff 失败: " + sanitize(e.getMessage(), token));
        }
    }

    private RepoDiffView fetchAndDiff(String name, boolean primary, String url,
                                      String baseBranch, String branch, Path cache, String token) {
        String key = GitRepoService.normalizeRemoteUrlKey(url);
        Object lock = locks.computeIfAbsent(key != null ? key : url, k -> new Object());
        synchronized (lock) {
            String authUrl = withToken(url, token);
            GitCli.Result baseFetch = GitCli.run(cache, 120, "git", "fetch", "--no-tags", authUrl,
                    "+refs/heads/" + baseBranch + ":" + REF_PREFIX + baseBranch);
            if (baseFetch.exitCode() != 0) {
                return RepoDiffView.error(name, primary,
                        "拉取基线分支失败: " + sanitize(baseFetch.err(), token));
            }
            GitCli.Result branchFetch = GitCli.run(cache, 120, "git", "fetch", "--no-tags", authUrl,
                    "+refs/heads/" + branch + ":" + REF_PREFIX + branch);
            if (branchFetch.exitCode() != 0) {
                // 会话进行中/异常退出时 runner 尚未 push，属正常时序而非故障
                return RepoDiffView.error(name, primary,
                        "会话分支尚未推送到远端（会话正常结束后才可查看）");
            }
            String range = REF_PREFIX + baseBranch + "..." + REF_PREFIX + branch;
            GitCli.Result stat = GitCli.run(cache, 30, "git", "diff", "--stat", range);
            GitCli.Result nameOnly = GitCli.run(cache, 30, "git", "diff", "--name-only", range);
            if (stat.exitCode() != 0 || nameOnly.exitCode() != 0) {
                return RepoDiffView.error(name, primary,
                        "diff 计算失败: " + sanitize(stat.err() + nameOnly.err(), token));
            }
            List<String> files = nameOnly.out().lines()
                    .map(String::trim).filter(l -> !l.isBlank()).toList();
            return RepoDiffView.of(name, primary, sanitize(stat.out(), token), files);
        }
    }

    /** token 仅内存使用；解析失败降级匿名（公开库可用，私有库 fetch 报错进 error 行）。 */
    private String resolveToken(SessionEntity ent, String url) {
        RepoGitGateway gw = repoGitGateway.getIfAvailable();
        if (gw == null) {
            return null;
        }
        try {
            return gw.resolveToken(ent.getCreatedBy(), hostOf(url), ent.getProjectId()).orElse(null);
        } catch (Exception e) {
            log.warn("远程 diff 凭据解析失败(降级匿名): repo={} err={}", url, e.getMessage());
            return null;
        }
    }

    private Project resolveProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        try {
            return projectService.requireProject(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    private static String branchOf(SessionEntity ent) {
        return "feature/" + ent.getId();
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** HTTPS URL 内嵌 PAT（仅进程参数；与 runner 侧 RunnerWorkspace.withToken 同约定） */
    private static String withToken(String url, String token) {
        if (token == null || token.isBlank()) {
            return url;
        }
        URI uri = URI.create(url.trim());
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://oauth2:").append(token).append('@').append(uri.getHost());
        if (uri.getPort() > 0) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        return sb.toString();
    }

    /** token 明文 + URL 编码形态一律替换为 ***（git 报错可能回显 URL） */
    private static String sanitize(String s, String token) {
        if (s == null || token == null || token.isEmpty()) {
            return s == null ? "" : s.strip();
        }
        String out = s.replace(token, "***");
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (!encoded.equals(token)) {
            out = out.replace(encoded, "***");
        }
        return out.strip();
    }
}
