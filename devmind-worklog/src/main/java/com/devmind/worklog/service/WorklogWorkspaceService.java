package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.project.ProjectService;
import com.devmind.project.model.ProjectEntity;
import com.devmind.worklog.dto.WorkspaceView;
import com.devmind.worklog.model.WorklogRepoEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * CAP-41 FR-01：工作日志空间懒创建与状态查询；CAP-74：空间 = 服务端 git 仓（事实源，
 * {@link WorklogRepoService#ensurePersonalRepo} 恒可建，不依赖节点）+ WORKLOG 项目
 * （仅 AI 生成/对话链路用，无平台默认节点时降级缺位不阻断仓——409 推迟到 launch 时由
 * 会话层给出）。
 *
 * <p>每用户一个 WORKLOG 项目（owner 隔离）；亲和节点 = 创建时的平台默认节点，固化进
 * 项目 agent_node_id 后不可改。</p>
 */
@Service
public class WorklogWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorklogWorkspaceService.class);

    private final ProjectService projectService;
    private final IdentityService identity;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final WorklogRepoService repoService;

    public WorklogWorkspaceService(ProjectService projectService, IdentityService identity,
                                   ObjectProvider<AgentNodeConnector> connectorProvider,
                                   WorklogRepoService repoService) {
        this.projectService = projectService;
        this.identity = identity;
        this.connectorProvider = connectorProvider;
        this.repoService = repoService;
    }

    /** 查询本人空间（未初始化返回 exists=false，不创建）。 */
    public WorkspaceView get() {
        String me = identity.currentActor();
        Optional<WorklogRepoEntity> repo = repoService.findActiveRepo(me);
        ProjectEntity project = projectService.findWorklogByOwner(me).orElse(null);
        if (repo.isEmpty() && project == null) {
            return WorkspaceView.absent();
        }
        return toView(repo.orElse(null), project, me);
    }

    /** 懒创建本人空间（幂等）：仓先行恒可建，项目尽力补建（无默认节点降级不 409）。 */
    public WorkspaceView ensure() {
        return ensureFor(identity.currentActor());
    }

    /**
     * 懒创建指定用户的空间（幂等）。调度线程无 SecurityContext，归属一律显式传 username。
     * CAP-74 验收 4：仓初始化不再被「无默认节点」阻断；项目缺位时 projectId=null，
     * AI 生成链路在 launch 时补建、仍无节点才 409。
     */
    public WorkspaceView ensureFor(String user) {
        WorklogRepoEntity repo = repoService.ensurePersonalRepo(user);
        var existing = projectService.findWorklogByOwner(user);
        if (existing.isPresent()) {
            return toView(repo, existing.get(), user);
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        String nodeId = connector != null ? connector.defaultNodeId() : null;
        if (nodeId == null || nodeId.isBlank()) {
            log.warn("无平台默认执行节点，工作日志项目暂不创建（仓已就绪，AI 生成不可用）: user={}", user);
            return toView(repo, null, user);
        }
        var view = projectService.ensureWorklogProject(user, nodeId);
        log.info("工作日志空间已初始化: user={} projectId={} node={}", user, view.id(), nodeId);
        return toView(repo, projectService.findWorklogByOwner(user).orElse(null), user);
    }

    private WorkspaceView toView(WorklogRepoEntity repo, ProjectEntity project, String username) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        String nodeId = project == null ? null : project.getAgentNodeId();
        Boolean online = connector != null && nodeId != null ? connector.isOnline(nodeId) : null;
        // 远端绑定恒展示个人仓口径（push 只推个人仓；活动仓可能是团队仓）
        WorklogRepoEntity personal = repoService.findPersonalRepo(username).orElse(null);
        String remoteUrl = personal != null ? personal.getRemoteUrl() : null;
        String remoteBranch = personal != null ? personal.getRemoteBranch() : null;
        return new WorkspaceView(true,
                repo == null ? null : repo.getRepoKey(),
                remoteUrl, remoteBranch,
                repo == null ? null : repoService.lastCommitAt(repo),
                project == null ? null : project.getId(),
                nodeId, online);
    }
}
