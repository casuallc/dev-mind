package com.devmind.session.service;

import com.devmind.common.agent.AgentLaunchCommand;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.agent.FinalizeResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitIdentityProvider.GitAuthor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-115 M3：工作区收口核心（会话级/需求级/终端工作区三入口共用的下发段）——
 * 协议门控 + specs 非空校验 + {@code workspace_finalize} 帧阻塞等 ack + 失败统一 409 文案。
 *
 * <p>抽出原因：终端工作区（term- 键）的收口与会话工作区走同一条 runner 语义
 * （ff-merge 基线 + push + 保留工作树），差异只在<b>参数来源</b>（会话行 + session_repos 快照
 * vs 终端工作区行 + repos_json 快照）与<b>落库</b>（各自实体各自置位）。公共段收敛到此类，
 * 保证三条链路的 runner 口径永不分叉。</p>
 *
 * <p>方法<b>禁 @Transactional</b>：内含 WS 阻塞等 ack（秒级），事务里等 ack 长占库连接
 * （同 SessionManagerService 收口红线）；状态落库由调用方在 ack 返回后各自完成。</p>
 */
@Component
public class WorkspaceFinalizeCore {

    private final ObjectProvider<AgentNodeConnector> connectorProvider;

    public WorkspaceFinalizeCore(ObjectProvider<AgentNodeConnector> connectorProvider) {
        this.connectorProvider = connectorProvider;
    }

    /**
     * 收口下发本体：
     *
     * @param nodeId            执行节点（blank → 409）
     * @param frameSessionId    帧 sessionId（审计聚合键；会话=会话 id，终端工作区=workspaceKey）
     * @param projectId         项目 id（可空，runner 侧仅作目录归属提示）
     * @param workspaceKey      工作区键（req-/sid-/term- 前缀；空 = 存量旧布局）
     * @param workspaceOwner    runner 侧工作区归属目录名（调用方已过白名单校验）
     * @param specs             仓库描述（含 token，仅随帧传输严禁进日志；空 → 409）
     * @param operator          收口 merge 提交署名（null = 帧不带身份字段，runner 回退内置署名）
     * @param discardChanges    true = 丢弃未提交改动（runner 侧 reset --hard + clean -fd）
     * @param deleteRemoteBranch true = 收口成功后删除远端会话/需求分支
     * @return runner ack（ok=true；!ok 时本方法已抛 409，调用方不会看到失败结果）
     */
    public FinalizeResult finalize(String nodeId, String frameSessionId, String projectId,
                                   String workspaceKey, String workspaceOwner,
                                   List<AgentLaunchCommand.RepoSpec> specs, GitAuthor operator,
                                   boolean discardChanges, boolean deleteRemoteBranch) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new DevMindException(ErrorCode.CONFLICT, "历史本机记录（无执行节点）不可收口");
        }
        AgentNodeConnector connector = requireConnector();
        requireWorkspaceProtocol(connector, nodeId, workspaceKey);
        if (specs == null || specs.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT, "无仓库快照，无法收口（非代码工作区）");
        }
        FinalizeResult result = connector.finalizeWorkspace(nodeId, frameSessionId, projectId,
                workspaceOwner, specs, discardChanges, workspaceKey, operator, deleteRemoteBranch);
        if (!result.ok()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "收口失败（工作区已保留，可处理后重试）: " + result.error());
        }
        return result;
    }

    /**
     * 协议门控（收口/释放/创建共用）：<b>带 workspaceKey</b> 要求 v10+（老 runner 忽略该字段会落回
     * {@code work/} 旧布局把不同工作区写进同一目录，属「必须认识」）；<b>缺 key</b> 退回 v7 门控
     * （存量旧布局，字段缺席 = 旧语义）。
     */
    public static void requireWorkspaceProtocol(AgentNodeConnector connector, String nodeId,
                                                String workspaceKey) {
        boolean keyed = workspaceKey != null && !workspaceKey.isBlank();
        if (keyed && !connector.supports(nodeId, AgentProtocol.REQUIREMENT_WORKSPACE)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 runner 版本过低，不支持需求粒度工作区（需协议 v10+），请升级该节点 runner");
        }
        if (!keyed && !connector.supports(nodeId, AgentProtocol.PER_USER_WORKSPACE)) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点 runner 版本过低，不支持每用户固定工作区（需协议 v7+），请升级该节点 runner");
        }
    }

    private AgentNodeConnector requireConnector() {
        AgentNodeConnector connector = connectorProvider != null ? connectorProvider.getIfAvailable() : null;
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        return connector;
    }
}
