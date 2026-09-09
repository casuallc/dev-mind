package com.devmind.execution.runner;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.audit.AuditLogEntity;
import com.devmind.common.audit.AuditLogRepository;
import com.devmind.execution.config.ExecutionProperties;
import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-36 节点执行器（统一执行底座，取代 CAP-07 RemoteStepRunner/SSH 通道）：
 * 步骤命令（服务端已渲染的完整脚本串）经 exec 帧下发 runner 节点本地执行，
 * exec_log 实时回流 sink（由调用方接 ExecutionLogHub），exec_exit 退出码收口。
 *
 * <p>repo 块非空时 runner 先准备构建工作区（克隆缓存 + detach checkout，token 随帧下发
 * 仅内存持有）；同一执行链（同 workspaceId）的后续步骤复用工作区，不再 fetch/checkout。
 * 执行审计沿用 CAP-07 FR-06 语义落 audit_logs（domain=agent_exec，accessType=agent）。</p>
 */
@Component
public class AgentNodeStepRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentNodeStepRunner.class);

    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final ExecutionProperties props;
    private final AuditLogRepository auditRepo;

    public AgentNodeStepRunner(ObjectProvider<AgentNodeConnector> connectorProvider,
                               ExecutionProperties props, AuditLogRepository auditRepo) {
        this.connectorProvider = connectorProvider;
        this.props = props;
        this.auditRepo = auditRepo;
    }

    /**
     * @param nodeId      目标节点（路由已由 {@link AgentNodeRouter} 完成）
     * @param projectId   项目 id（工作区归属；无项目上下文可空）
     * @param workspaceId 同链共享工作区 id（带 repo 时必填；如 build-&lt;id&gt;）
     * @param stepIndex   步骤序号（拼 execId 用，保证帧路由键唯一）
     * @param step        步骤（command = 渲染后的完整脚本串）
     * @param env         注入子进程的环境变量
     * @param repo        构建工作区描述（可空 = 不准备代码，runner 本地目录执行）
     */
    public StepResult runStep(String nodeId, String projectId, String workspaceId, int stepIndex,
                              StepSpec step, Map<String, String> env, AgentExecCommand.Repo repo,
                              Consumer<String> sink) {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            return StepResult.failed(-1, "agent 模块未装配，无可用执行节点");
        }
        String execId = (workspaceId == null || workspaceId.isBlank() ? "exec-" + nodeId : workspaceId)
                + "-s" + stepIndex + "-" + Long.toString(System.nanoTime(), 36);
        long timeoutSec = props.getStepTimeoutMs() / 1000;
        AgentExecCommand cmd = new AgentExecCommand(execId, projectId, workspaceId, step.command(),
                step.workingDir(), env, timeoutSec, repo);
        long start = System.currentTimeMillis();
        AgentExecResult r;
        try {
            r = connector.exec(nodeId, cmd, sink);
        } catch (Exception e) {
            String msg = rootMessage(e);
            sink.accept("[stderr] " + msg);
            audit(projectId, nodeId, step, -1, false, msg, start);
            return StepResult.failed(-1, msg);
        }
        if (r.error() != null) {
            sink.accept("[stderr] " + r.error());
            audit(projectId, nodeId, step, r.exitCode(), false, r.error(), start);
            return StepResult.failed(r.exitCode(), r.error());
        }
        if (r.timedOut()) {
            String err = "步骤超时（>" + timeoutSec + "s，runner 侧已整树终止）：" + step.name();
            audit(projectId, nodeId, step, r.exitCode(), false, err, start);
            return StepResult.failed(r.exitCode(), err);
        }
        audit(projectId, nodeId, step, r.exitCode(), r.exitCode() == 0,
                r.exitCode() == 0 ? "ok" : "exit=" + r.exitCode(), start);
        return new StepResult(r.exitCode() == 0, r.exitCode(),
                r.exitCode() == 0 ? null : "exit=" + r.exitCode());
    }

    /** 执行审计（沿用 CAP-07 FR-06 全量留痕语义；serverId 列复用存节点 id）。 */
    private void audit(String projectId, String nodeId, StepSpec step, int exitCode,
                       boolean success, String detail, long startMs) {
        try {
            AuditLogEntity a = new AuditLogEntity();
            a.setDomain("agent_exec");
            a.setProjectId(projectId);
            a.setServerId(nodeId == null || nodeId.isBlank() ? null : Long.parseLong(nodeId));
            a.setAccessType("agent");
            a.setAction("execute");
            a.setCapability(step.location());
            a.setCommand(truncate(step.command(), 4000));
            a.setExitCode(exitCode);
            a.setSuccess(success);
            a.setDetail(truncate(detail, 2000));
            a.setDurationMs(System.currentTimeMillis() - startMs);
            a.setCreatedAt(Instant.now());
            auditRepo.save(a);
        } catch (NumberFormatException e) {
            log.debug("节点 id 非数值，审计跳过 serverId: {}", nodeId);
        } catch (Exception e) {
            log.warn("exec 审计写入失败（不影响执行结果）: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
