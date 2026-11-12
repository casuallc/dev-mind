package com.devmind.session.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.common.util.GitCli;
import com.devmind.project.GitRepoService;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.RepoView;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 远端 feature 分支管理（工作区管理 tab）：列出/删除项目各库远端的 {@code feature/*} 分支。
 * 列举走 {@code git ls-remote}（无需本地克隆）；删除走 {@code git push --delete}——push 需要
 * repo 上下文，优先复用服务端克隆缓存（{@link GitRepoService#deriveClonePath}），缓存缺失时
 * 临时 {@code git init} 目录兜底（实测空仓库即可 push）。
 *
 * <p>凭据红线同 {@link RemoteDiffService}：token 仅存内存、URL 内嵌注入（oauth2 约定）、
 * 所有 git 输出脱敏后才进错误消息/日志，绝不落库。单库列举失败不拖垮整组（错误随行返回）。</p>
 */
@Service
public class RemoteBranchAdminService {

    private static final Logger log = LoggerFactory.getLogger(RemoteBranchAdminService.class);
    private static final String BRANCH_PREFIX = "feature/";

    public record RemoteBranchRow(String repoName, String branch, String sha) {
    }

    /** 列举结果：branches 为命中行；errors 为逐库失败原因（页面 warning 展示，不阻断）。 */
    public record RemoteBranchList(List<RemoteBranchRow> branches, List<String> errors) {
    }

    private final ProjectService projectService;
    private final GitRepoService gitRepoService;
    private final ObjectProvider<RepoGitGateway> repoGitGateway;
    private final ObjectProvider<EgressProxyRouter> egressRouterProvider;
    private final IdentityService identityService;

    public RemoteBranchAdminService(ProjectService projectService,
                                    GitRepoService gitRepoService,
                                    ObjectProvider<RepoGitGateway> repoGitGateway,
                                    ObjectProvider<EgressProxyRouter> egressRouterProvider,
                                    IdentityService identityService) {
        this.projectService = projectService;
        this.gitRepoService = gitRepoService;
        this.repoGitGateway = repoGitGateway;
        this.egressRouterProvider = egressRouterProvider;
        this.identityService = identityService;
    }

    public RemoteBranchList list(String projectId) {
        List<RemoteBranchRow> branches = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (RepoView repo : projectService.listRepos(projectId)) {
            String url = repo.remoteUrl() == null ? "" : repo.remoteUrl().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                continue; // ssh/无远端：无凭据可走 HTTP 通道，跳过
            }
            String token = resolveToken(url, projectId);
            try {
                List<String> cmd = new ArrayList<>(List.of("git"));
                cmd.addAll(egressProxyArgs(url));
                cmd.addAll(List.of("ls-remote", withToken(url, token),
                        "refs/heads/" + BRANCH_PREFIX + "*"));
                GitCli.Result r = GitCli.run(null, 60, cmd.toArray(String[]::new));
                if (r.exitCode() != 0) {
                    errors.add(repo.name() + ": 列举失败: " + sanitize(r.err(), token));
                    continue;
                }
                for (String line : r.out().lines().toList()) {
                    String[] parts = line.split("\\s+");
                    if (parts.length == 2 && parts[1].startsWith("refs/heads/" + BRANCH_PREFIX)) {
                        branches.add(new RemoteBranchRow(repo.name(),
                                parts[1].substring("refs/heads/".length()),
                                parts[0].length() > 12 ? parts[0].substring(0, 12) : parts[0]));
                    }
                }
            } catch (Exception e) {
                errors.add(repo.name() + ": 列举异常: " + sanitize(e.getMessage(), token));
            }
        }
        branches.sort(Comparator.comparing(RemoteBranchRow::repoName).thenComparing(RemoteBranchRow::branch));
        return new RemoteBranchList(branches, errors);
    }

    /** 删除远端 feature 分支（幂等：远端已不在视为成功）。 */
    public void delete(String projectId, String repoName, String branch) {
        if (branch == null || !branch.startsWith(BRANCH_PREFIX)
                || branch.contains("..") || branch.contains(" ") || branch.contains("~")
                || branch.contains("^") || branch.contains(":")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "只允许删除 feature/ 前缀的远端分支: " + branch);
        }
        RepoView repo = projectService.listRepos(projectId).stream()
                .filter(r -> repoName != null && repoName.equals(r.name()))
                .findFirst()
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "仓库不存在: " + repoName));
        String url = repo.remoteUrl() == null ? "" : repo.remoteUrl().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new DevMindException(ErrorCode.CONFLICT, "该库无可用 http(s) 远端地址");
        }
        String token = resolveToken(url, projectId);
        Path cache = gitRepoService.deriveClonePath(url);
        boolean temp = !Files.isDirectory(cache.resolve(".git"));
        Path work = cache;
        try {
            if (temp) {
                // push 需要 repo 上下文；服务端克隆未就绪时用临时空仓库兜底
                work = Files.createTempDirectory("devmind-branch-del");
                GitCli.Result init = GitCli.run(work, 30, "git", "init");
                if (init.exitCode() != 0) {
                    throw new DevMindException(ErrorCode.INTERNAL,
                            "临时仓库初始化失败: " + sanitize(init.err(), token));
                }
            }
            List<String> cmd = new ArrayList<>(List.of("git"));
            cmd.addAll(egressProxyArgs(url));
            cmd.addAll(List.of("push", withToken(url, token), "--delete",
                    "refs/heads/" + branch));
            GitCli.Result r = GitCli.run(work, 60, cmd.toArray(String[]::new));
            if (r.exitCode() != 0
                    && !(r.err() != null && r.err().contains("remote ref does not exist"))) {
                throw new DevMindException(ErrorCode.CONFLICT,
                        "删除远端分支失败: " + sanitize(r.err(), token));
            }
            log.info("远端分支已删除: project={} repo={} branch={} by={}",
                    projectId, repoName, branch, identityService.currentActor());
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL,
                    "删除远端分支异常: " + sanitize(e.getMessage(), token));
        } finally {
            if (temp && work != null) {
                deleteRecursively(work);
            }
        }
    }

    /** token 仅内存使用；解析失败降级匿名（公开库可用，私有库操作报错上行）。 */
    private String resolveToken(String url, String projectId) {
        RepoGitGateway gw = repoGitGateway.getIfAvailable();
        if (gw == null) {
            return null;
        }
        try {
            return gw.resolveToken(identityService.currentActor(), hostOf(url), projectId)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("远端分支管理凭据解析失败(降级匿名): err={}", e.getMessage());
            return null;
        }
    }

    private void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            log.debug("临时目录清理失败(忽略): {} err={}", dir, e.getMessage());
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** HTTPS URL 内嵌 PAT（仅进程参数；与 RemoteDiffService 同约定） */
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

    /** 出口代理注入参数（与 RemoteDiffService.egressProxyArgs 同语义） */
    private List<String> egressProxyArgs(String cleanUrl) {
        EgressProxyRouter router = egressRouterProvider.getIfAvailable();
        if (router == null) {
            return List.of();
        }
        Optional<String> proxyUrl = router.gitProxyUrl(cleanUrl);
        if (proxyUrl.isEmpty()) {
            return List.of();
        }
        URI uri;
        try {
            uri = URI.create(cleanUrl.trim());
        } catch (Exception e) {
            return List.of();
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return List.of();
        }
        String authority = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        return List.of("-c", "http." + uri.getScheme() + "://" + authority + ".proxy=" + proxyUrl.get());
    }

    /** token 明文 + URL 编码形态一律替换为 ***（git 报错可能回显 URL） */
    private static String sanitize(String s, String token) {
        if (s == null) {
            return "";
        }
        if (token == null || token.isEmpty()) {
            return s.strip();
        }
        String out = s.replace(token, "***");
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (!encoded.equals(token)) {
            out = out.replace(encoded, "***");
        }
        return out.strip();
    }
}
