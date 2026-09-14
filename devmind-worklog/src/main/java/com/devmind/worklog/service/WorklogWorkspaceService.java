package com.devmind.worklog.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.model.ProjectEntity;
import com.devmind.worklog.dto.WorkspaceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * CAP-41 FR-01：工作日志空间（WORKLOG 特殊项目）懒创建与状态查询。
 *
 * <p>每用户一个 WORKLOG 项目（owner 隔离）；亲和节点 = 创建时的平台默认节点，固化进
 * 项目 agent_node_id 后不可改（事实源在该节点本地 {user.home}/worklog/&lt;username&gt;，
 * 换节点 = 换一份空日志，不静默漂移）。agent 模块未装配时空间不可用（409）。</p>
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
        return projectService.findWorklogByOwner(identity.currentActor())
                .map(this::toView)
                .orElseGet(WorkspaceView::absent);
    }

    /** 懒创建本人空间（幂等）：亲和节点取平台默认节点，无默认节点 409 明确提示。 */
    public WorkspaceView ensure() {
        String user = identity.currentActor();
        var existing = projectService.findWorklogByOwner(user);
        if (existing.isPresent()) {
            return toView(existing.get());
        }
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        String nodeId = connector != null ? connector.defaultNodeId() : null;
        if (nodeId == null || nodeId.isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "无平台默认执行节点，无法初始化工作日志空间（请先注册 runner 节点并设为默认）");
        }
        var view = projectService.ensureWorklogProject(user, nodeId);
        log.info("工作日志空间已初始化: user={} projectId={} node={}", user, view.id(), nodeId);
        return toView(projectService.findWorklogByOwner(user).orElseThrow());
    }

    private WorkspaceView toView(ProjectEntity e) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        Boolean online = connector != null && e.getAgentNodeId() != null
                ? connector.isOnline(e.getAgentNodeId()) : null;
        return new WorkspaceView(true, e.getId(), e.getName(), e.getPath(), e.getAgentNodeId(), online);
    }
}
