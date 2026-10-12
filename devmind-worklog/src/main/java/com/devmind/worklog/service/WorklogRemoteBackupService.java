package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.WorklogPushResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.integration.service.GitRemoteOps;
import com.devmind.worklog.model.WorklogRepoEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * CAP-74 M1：工作日志服务端 push（事实源在服务端仓，不再下发 runner）。
 * 全程仓级锁内：远端分支不存在 → 直接 push；否则 fetch → merge --no-edit（分叉自动合，
 * 跨人路径不相交恒干净）→ 冲突 mergeAbort 清 MERGING + 409 带冲突文件清单（显式
 * {@link #resolveConflict} 端点解决，不静默覆盖）→ push（本地恒 main，远端分支可配，refspec）。
 *
 * <p>绑定信息存 worklog_repos 个人仓行（remote_url/remote_branch）；凭证复用 CAP-35 个人
 * PAT 链——按远端 host 经 {@link RepoGitGateway#resolveToken} 解析（token 仅进程参数注入，
 * 不落盘不进日志）；file:// 本地验证通道免凭证。</p>
 */
@Service
public class WorklogRemoteBackupService {

    private static final Logger log = LoggerFactory.getLogger(WorklogRemoteBackupService.class);

    private static final String LOCAL_BRANCH = "main";

    private final IdentityService identity;
    private final WorklogRepoService repoService;
    private final GitRemoteOps git;
    private final ObjectProvider<RepoGitGateway> gitGatewayProvider;

    public WorklogRemoteBackupService(IdentityService identity, WorklogRepoService repoService,
                                      GitRemoteOps git, ObjectProvider<RepoGitGateway> gitGatewayProvider) {
        this.identity = identity;
        this.repoService = repoService;
        this.git = git;
        this.gitGatewayProvider = gitGatewayProvider;
    }

    /** 推送本人个人仓到已绑定远端。各前置不满足抛 400/409；传输层失败不抛，看 ok。 */
    public WorklogPushResult pushToRemote() {
        String me = identity.currentActor();
        WorklogRepoEntity repo = repoService.ensurePersonalRepo(me);
        String remoteUrl = requireRemote(repo);
        String branch = remoteBranch(repo);
        String token = resolveToken(me, remoteUrl);
        log.info("worklog 服务端 push: user={} repo={} url={} branch={}", me, repo.getRepoKey(), remoteUrl, branch);
        return repoService.withLock(repo.getRepoKey(), () -> fetchMergePush(repo, remoteUrl, branch, token));
    }

    /**
     * 冲突显式解决（push 409 后前端二选一）：strategy=ours 以平台仓为准 / theirs 以远端为准。
     * 锁内重新 fetch（远端可能已动）→ merge -X strategy → push。actor 必须是仓成员。
     */
    public WorklogPushResult resolveConflict(String repoKey, String strategy) {
        String me = identity.currentActor();
        WorklogRepoEntity repo = repoService.requireRepo(repoKey);
        if (!repoService.isMember(repo, me)) {
            throw new DevMindException(ErrorCode.FORBIDDEN, "你不是仓 " + repoKey + " 的成员");
        }
        String remoteUrl = requireRemote(repo);
        String branch = remoteBranch(repo);
        String token = resolveToken(me, remoteUrl);
        return repoService.withLock(repoKey, () -> {
            String dir = repoService.repoDir(repoKey).toString();
            GitRemoteOps.GitResult fetch = git.fetchAllRefs(dir, remoteUrl, token);
            if (!fetch.ok()) {
                return WorklogPushResult.failed("fetch 失败: " + fetch.output());
            }
            GitRemoteOps.GitResult merge = git.mergeWithStrategy(dir, "origin/" + branch, strategy);
            if (!merge.ok()) {
                git.mergeAbort(dir);
                throw new DevMindException(ErrorCode.CONFLICT,
                        "策略合并仍失败（" + strategy + "），已回滚合并状态: " + merge.output());
            }
            GitRemoteOps.GitResult push = git.pushBranch(dir, LOCAL_BRANCH, branch, remoteUrl, token);
            if (!push.ok()) {
                return WorklogPushResult.failed("push 失败: " + push.output());
            }
            log.info("worklog 冲突已解决并推送: user={} repo={} strategy={}", me, repoKey, strategy);
            return WorklogPushResult.ok("已按「" + ("ours".equals(strategy) ? "平台仓为准" : "远端为准")
                    + "」合并并推送到 " + branch);
        });
    }

    /** 个人仓冲突的快捷解决入口（POST /worklog/workspace/resolve）：key 由当前用户推导，免前端拼 personal-<username>。 */
    public WorklogPushResult resolvePersonalConflict(String strategy) {
        return resolveConflict(WorklogRepoService.personalRepoKey(identity.currentActor()), strategy);
    }

    // ---------------- 内部 ----------------

    /** fetch + merge + push 主链（调用方已持仓锁）。 */
    private WorklogPushResult fetchMergePush(WorklogRepoEntity repo, String remoteUrl,
                                             String branch, String token) {
        String dir = repoService.repoDir(repo.getRepoKey()).toString();
        // 远端分支不存在 → 首次直推
        GitRemoteOps.GitResult ls = git.lsRemote(remoteUrl, token, "refs/heads/" + branch);
        if (!ls.ok()) {
            return WorklogPushResult.failed("远端探测失败: " + ls.output());
        }
        if (!ls.output().isBlank()) {
            GitRemoteOps.GitResult fetch = git.fetchAllRefs(dir, remoteUrl, token);
            if (!fetch.ok()) {
                return WorklogPushResult.failed("fetch 失败: " + fetch.output());
            }
            GitRemoteOps.GitResult merge = git.mergeNoEdit(dir, "origin/" + branch);
            if (!merge.ok()) {
                git.mergeAbort(dir); // 清 MERGING，仓不留半合并状态
                throw new DevMindException(ErrorCode.CONFLICT,
                        "与远端分支 " + branch + " 合并冲突，请显式选择解决方向（以平台为准/以远端为准）。冲突文件: "
                                + conflictFiles(merge.output()));
            }
        }
        GitRemoteOps.GitResult push = git.pushBranch(dir, LOCAL_BRANCH, branch, remoteUrl, token);
        if (!push.ok()) {
            return WorklogPushResult.failed("push 失败: " + push.output());
        }
        return WorklogPushResult.ok("已推送到远端分支 " + branch);
    }

    /** 从 merge 输出提取 CONFLICT 行（冲突文件清单，随 409 给前端展示）。 */
    private static String conflictFiles(String output) {
        if (output == null) {
            return "（未识别）";
        }
        String files = output.lines()
                .filter(l -> l.contains("CONFLICT"))
                .map(l -> l.replaceFirst(".*Merge conflict in ", "").trim())
                .collect(Collectors.joining(", "));
        return files.isBlank() ? "（未识别）" : files;
    }

    private static String requireRemote(WorklogRepoEntity repo) {
        if (repo.getRemoteUrl() == null || repo.getRemoteUrl().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "未绑定远程仓库（在「设置 → 工作日志」的远程仓库备份里配置 URL 后再推送）");
        }
        return repo.getRemoteUrl();
    }

    private static String remoteBranch(WorklogRepoEntity repo) {
        return repo.getRemoteBranch() == null || repo.getRemoteBranch().isBlank()
                ? "main" : repo.getRemoteBranch().trim();
    }

    /** file:// 无凭证语义；http/https 按 host 经 CAP-35 个人 PAT 解析，缺失即 409 引导。 */
    private String resolveToken(String me, String remoteUrl) {
        String scheme;
        String host;
        try {
            URI uri = URI.create(remoteUrl);
            scheme = uri.getScheme();
            host = uri.getHost();
        } catch (IllegalArgumentException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "远端仓库 URL 无法解析: " + remoteUrl);
        }
        if ("file".equalsIgnoreCase(scheme)) {
            return null;
        }
        RepoGitGateway gateway = gitGatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "integration 模块未装配，无法解析推送凭证（file:// 本地远端不受影响）");
        }
        return gateway.resolveToken(me, host, null)
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT,
                        "未找到 " + host + " 的个人访问令牌（请先在「设置 → 第三方账号」配置该 host 的 PAT）"));
    }
}
