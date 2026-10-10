package com.devmind.flow.verify;

import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProtocol;
import com.devmind.common.agent.AgentVerifyCommand;
import com.devmind.common.agent.AgentVerifyResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * CAP-73 {@link VerifyNodeGateway} 的 runner WS 实现：三阶段指令经 common 的
 * {@link AgentNodeConnector} SPI 下发（实现 = devmind-agent 的 AgentConnectionRegistry，
 * ObjectProvider 探测防模块缺席）。节点失败一律折算为结果记录返回（不抛），
 * 只有「通道未装配」这种结构性缺失才抛 CONFLICT（触发期已被预检拦住，编排期属异常路径）。
 */
@Component
public class AgentVerifyNodeGateway implements VerifyNodeGateway {

    private final ObjectProvider<AgentNodeConnector> connectorProvider;

    public AgentVerifyNodeGateway(ObjectProvider<AgentNodeConnector> connectorProvider) {
        this.connectorProvider = connectorProvider;
    }

    @Override
    public boolean isOnline(String nodeId) {
        AgentNodeConnector c = connectorProvider.getIfAvailable();
        return c != null && c.isOnline(nodeId);
    }

    @Override
    public boolean supports(String nodeId, int minVersion) {
        AgentNodeConnector c = connectorProvider.getIfAvailable();
        return c != null && c.supports(nodeId, minVersion);
    }

    @Override
    public VerifyNodeResult push(String nodeId, VerifyPushCommand cmd, Consumer<String> sink) {
        AgentVerifyResult r = safeCall(() -> connector().verifyPush(nodeId,
                new AgentVerifyCommand.Push(requestId("vp", 0), 0L, cmd.projectId(),
                        cmd.workspaceOwner(), cmd.workspaceKey(), cmd.remoteUrl(), cmd.branch(),
                        cmd.repoToken(), cmd.timeoutSec()), sink));
        return r.ok() ? VerifyNodeResult.success() : VerifyNodeResult.failed(r.error());
    }

    @Override
    public VerifyPackResult pack(String nodeId, VerifyPackCommand cmd, Consumer<String> sink) {
        List<AgentVerifyCommand.Pack.Step> steps = cmd.steps().stream()
                .map(s -> new AgentVerifyCommand.Pack.Step(s.name(), s.command(), s.workingDir()))
                .toList();
        AgentVerifyResult r = safeCall(() -> connector().verifyPack(nodeId,
                new AgentVerifyCommand.Pack(requestId("vk", cmd.runId()), cmd.runId(),
                        cmd.projectId(), cmd.workspaceKey(), cmd.remoteUrl(), cmd.branch(),
                        cmd.commitSha(), cmd.repoToken(), steps, cmd.outputDir(), cmd.env(),
                        cmd.timeoutSec(), cmd.bundleMaxBytes()), sink));
        return r.ok() ? new VerifyPackResult(true, null, r.fileCount())
                : VerifyPackResult.failed(r.error());
    }

    @Override
    public VerifyPatchResult patch(String nodeId, VerifyPatchCommand cmd, Consumer<String> sink) {
        AgentVerifyResult r = safeCall(() -> connector().verifyPatch(nodeId,
                new AgentVerifyCommand.Patch(requestId("vt", cmd.runId()), cmd.runId(),
                        cmd.patchDir(), cmd.preHook(), cmd.postHook(), cmd.manifestKey(),
                        cmd.timeoutSec()), sink));
        return r.ok() ? new VerifyPatchResult(true, null, r.filesChanged())
                : VerifyPatchResult.failed(r.error());
    }

    private AgentVerifyResult safeCall(java.util.function.Supplier<AgentVerifyResult> call) {
        try {
            return call.get();
        } catch (DevMindException e) {
            return AgentVerifyResult.failed(e.getMessage());
        }
    }

    private AgentNodeConnector connector() {
        AgentNodeConnector c = connectorProvider.getIfAvailable();
        if (c == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无可用执行节点");
        }
        return c;
    }

    /** 帧路由键：[a-z0-9-] 形态（runner SAFE_ID 兼容），按 runId + 随机段防重 */
    private static String requestId(String prefix, long runId) {
        return prefix + (runId > 0 ? "-" + runId : "")
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 门控版本透传（与 AgentProtocol.VERIFY_FRAMES 对齐，单点改常量） */
    public static final int REQUIRED_PROTOCOL = AgentProtocol.VERIFY_FRAMES;
}
