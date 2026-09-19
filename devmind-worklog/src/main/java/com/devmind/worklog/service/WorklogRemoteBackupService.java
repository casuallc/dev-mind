package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.WorklogPushResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.RepoGitGateway;
import com.devmind.project.ProjectService;
import com.devmind.project.model.ProjectEntity;
import com.devmind.worklog.repo.WorklogUserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;

/**
 * CAP-41 M3：工作日志空间远端备份（手动触发）。绑定信息存个人设置
 * （remote_url/remote_branch）；凭证复用 CAP-35 个人 PAT 链——按远端 URL 的 host 经
 * {@link RepoGitGateway#resolveToken} 解析（token 解密不出 integration 模块边界，
 * 仅随 worklog_push 帧下发 runner，不进日志）。push 实际在 runner 侧持久工作区执行。
 */
@Service
public class WorklogRemoteBackupService {

    private static final Logger log = LoggerFactory.getLogger(WorklogRemoteBackupService.class);

    private final WorklogUserSettingsRepository settingsRepo;
    private final IdentityService identity;
    private final ProjectService projectService;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final ObjectProvider<RepoGitGateway> gitGatewayProvider;

    public WorklogRemoteBackupService(WorklogUserSettingsRepository settingsRepo,
                                      IdentityService identity, ProjectService projectService,
                                      ObjectProvider<AgentNodeConnector> connectorProvider,
                                      ObjectProvider<RepoGitGateway> gitGatewayProvider) {
        this.settingsRepo = settingsRepo;
        this.identity = identity;
        this.projectService = projectService;
        this.connectorProvider = connectorProvider;
        this.gitGatewayProvider = gitGatewayProvider;
    }

    /** 推送本人工作日志空间到已绑定远端。各前置不满足抛 400/409；push 失败不抛，看 ok。 */
    public WorklogPushResult pushToRemote() {
        String me = identity.currentActor();
        var settings = settingsRepo.findByUserId(me)
                .filter(s -> s.getRemoteUrl() != null && !s.getRemoteUrl().isBlank())
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                        "未绑定远程仓库（在工时设置-远程仓库备份里配置 URL 后再推送）"));
        String remoteUrl = settings.getRemoteUrl();
        String branch = settings.getRemoteBranch() == null || settings.getRemoteBranch().isBlank()
                ? "main" : settings.getRemoteBranch().trim();

        String token = resolveToken(me, remoteUrl);

        ProjectEntity project = projectService.findWorklogByOwner(me)
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT,
                        "工作日志空间未初始化（先点「初始化空间」再推送）"));
        if (project.getAgentNodeId() == null || project.getAgentNodeId().isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT, "工作日志空间无亲和节点，无法推送");
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        log.info("worklog 远端备份下发: user={} node={} url={} branch={}",
                me, project.getAgentNodeId(), remoteUrl, branch);
        return connector.pushWorklog(project.getAgentNodeId(), me, remoteUrl, branch, token);
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
