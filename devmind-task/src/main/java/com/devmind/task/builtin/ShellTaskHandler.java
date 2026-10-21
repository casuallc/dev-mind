package com.devmind.task.builtin;

import com.devmind.common.agent.AgentExecCommand;
import com.devmind.common.agent.AgentExecResult;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.exception.DevMindException;
import com.devmind.execution.runner.AgentNodeRouter;
import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.NonRetryableTaskException;
import com.devmind.task.api.TaskContext;
import com.devmind.task.model.AsyncTaskEntity;
import com.devmind.task.repo.AsyncTaskRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置 shell 任务 {@code builtin.shell}（CAP-80 M4）：把 payload.command 下发 runner 节点执行——
 * 复用 CAP-36 exec 帧族（协议 v3+，零协议改动零 runner 发版），repo=null 不准备代码，
 * 直接在 runner 本地工作目录跑；stdout/stderr 行流实时入任务日志（stderr 行带「[stderr] 」前缀）。
 *
 * <p>payload JSON：{@code {"command": "...", "workingDir": "sub/dir", "env": {"K": "V"},
 * "timeoutSec": 600}}。command 必填（空 = 不可重试失败——重试同一条空命令没有意义）；
 * timeoutSec 缺省 min(任务 timeoutMs/1000, 1800)。exit 0 成功；非零/超时按任务重试策略重排。</p>
 *
 * <p>已知限制（CAP-80 R6）：exec 帧族无取消协议——任务 cancel 只中断服务端等待线程，
 * runner 侧进程由自身 timeoutSec 整树 kill 兜底。</p>
 */
@Component
public class ShellTaskHandler implements AsyncTaskHandler {

    public static final String TYPE = "builtin.shell";
    /** exec 帧超时上限（秒）：缺省口径 cap 住，避免任务 timeoutMs 配成天文数字后 runner 进程无界 */
    private static final long DEFAULT_TIMEOUT_CAP_SEC = 1800;

    private final ObjectMapper mapper;
    private final AgentNodeRouter router;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final AsyncTaskRepository taskRepo;

    public ShellTaskHandler(ObjectMapper mapper, AgentNodeRouter router,
                            ObjectProvider<AgentNodeConnector> connectorProvider,
                            AsyncTaskRepository taskRepo) {
        this.mapper = mapper;
        this.router = router;
        this.connectorProvider = connectorProvider;
        this.taskRepo = taskRepo;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String description() {
        return "在 runner 节点执行 shell 命令（过节点 execAllowlist 白名单，行流实时回传）";
    }

    @Override
    public String payloadTemplate() {
        return "{\n  \"command\": \"echo hello\"\n}";
    }

    @Override
    public String executor() {
        return EXECUTOR_RUNNER;
    }

    @Override
    public void execute(String payload, TaskContext ctx) throws Exception {
        JsonNode p = payload == null || payload.isBlank() ? mapper.createObjectNode() : mapper.readTree(payload);
        String command = p.path("command").asText("");
        if (command.isBlank()) {
            throw new NonRetryableTaskException("payload.command 不能为空");
        }
        String workingDir = p.path("workingDir").asText(null);
        Map<String, String> env = null;
        JsonNode envNode = p.path("env");
        if (envNode.isObject()) {
            env = new HashMap<>();
            for (Map.Entry<String, JsonNode> e : envNode.properties()) {
                env.put(e.getKey(), e.getValue().asText());
            }
        }
        AsyncTaskEntity task = taskRepo.findById(ctx.taskId())
                .orElseThrow(() -> new IllegalStateException("任务记录不存在: " + ctx.taskId()));
        long timeoutSec = p.path("timeoutSec").isNumber()
                ? p.path("timeoutSec").asLong()
                : Math.min(task.getTimeoutMsOrDefault() / 1000, DEFAULT_TIMEOUT_CAP_SEC);

        // 路由链（M4 FR-14）：显式 agent_node_id → 平台默认节点 → 皆无命中 409；
        // 在线性与协议版本（exec 需 v3+）在执行时判定（节点上下线是常态，不该卡创建）
        String nodeId = router.route(task.getAgentNodeId(), null, List.of());
        router.requireExecCapable(nodeId);
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(com.devmind.common.exception.ErrorCode.CONFLICT,
                    "agent 模块未装配，无可用执行节点");
        }
        ctx.log("下发节点 " + nodeId + " 执行（超时 " + timeoutSec + "s）: " + command);
        AgentExecCommand cmd = new AgentExecCommand(ctx.runId(), task.getProjectId(), null,
                command, workingDir, env, timeoutSec, null);
        AgentExecResult result = connector.exec(nodeId, cmd, ctx::log);
        if (result.ok()) {
            ctx.log("runner 执行完成（exit 0）");
            return;
        }
        if (result.timedOut()) {
            throw new IllegalStateException("runner 执行超时（" + timeoutSec + "s），runner 已整树 kill");
        }
        if (result.error() != null) {
            throw new IllegalStateException("runner 执行失败: " + result.error());
        }
        throw new IllegalStateException("命令非零退出: exit=" + result.exitCode());
    }
}
