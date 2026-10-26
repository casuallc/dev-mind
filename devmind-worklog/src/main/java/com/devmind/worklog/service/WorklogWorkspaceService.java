package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.project.ProjectService;
import com.devmind.project.model.ProjectEntity;
import com.devmind.worklog.dto.WorkspaceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * CAP-41 FR-01：工作日志空间懒创建与状态查询。CAP-89：服务端 git 仓链路退役后，
 * 空间 = WORKLOG 项目（仅 AI 生成/对话链路用，无平台默认节点时降级缺位——读/编辑
 * 直接落库不受影响，409 推迟到 launch 时由会话层给出）。
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

    public WorklogWorkspaceService(ProjectService projectService, IdentityService identity,
                                   ObjectProvider<AgentNodeConnector> connectorProvider) {
        this.projectService = projectService;
        this.identity = identity;
        this.connectorProvider = connectorProvider;
    }

    /** 查询本人空间（未初始化返回 exists=false，不创建）。 */
    public WorkspaceView get() {
        String me = identity.currentActor();
        ProjectEntity project = projectService.findWorklogByOwner(me).orElse(null);
        if (project == null) {
            return WorkspaceView.absent();
        }
        return toView(project);
    }

    /** 懒创建本人空间（幂等）。 */
    public WorkspaceView ensure() {
        return ensureFor(identity.currentActor());
    }

    /**
     * 懒创建指定用户的空间（幂等）。调度线程无 SecurityContext，归属一律显式传 username。
     * 无平台默认节点时项目降级缺位（projectId=null），AI 生成链路在 launch 时补建、
     * 仍无节点才 409。
     */
    public WorkspaceView ensureFor(String user) {
        var existing = projectService.findWorklogByOwner(user);
        if (existing.isPresent()) {
            return toView(existing.get());
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        String nodeId = connector != null ? connector.defaultNodeId() : null;
        if (nodeId == null || nodeId.isBlank()) {
            log.warn("无平台默认执行节点，工作日志项目暂不创建（AI 生成不可用）: user={}", user);
            return WorkspaceView.absent();
        }
        var view = projectService.ensureWorklogProject(user, nodeId);
        log.info("工作日志空间已初始化: user={} projectId={} node={}", user, view.id(), nodeId);
        return toView(projectService.findWorklogByOwner(user).orElse(null));
    }

    private WorkspaceView toView(ProjectEntity project) {
        if (project == null) {
            return WorkspaceView.absent();
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        String nodeId = project.getAgentNodeId();
        Boolean online = connector != null && nodeId != null ? connector.isOnline(nodeId) : null;
        return new WorkspaceView(true, project.getId(), nodeId, online);
    }
}
