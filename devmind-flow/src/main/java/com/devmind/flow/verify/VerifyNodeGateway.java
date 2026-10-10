package com.devmind.flow.verify;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CAP-73 验证链节点通道（flow 侧接口，实现 = devmind-agent WS 帧适配，
 * 协议版本门控 VERIFY_FRAMES 在实现内完成）。三类节点角色均由它寻址：
 * 会话节点（push）、构建节点（pack）、环境节点（patch）。
 *
 * <p>所有方法同步等待 runner ack/结果帧返回；节点离线/超时以结果对象 error 表达，
 * 不抛异常（编排器统一收口为阶段 FAILED）。</p>
 */
public interface VerifyNodeGateway {

    boolean isOnline(String nodeId);

    /** 节点协议版本是否 ≥ minVersion（VERIFY_RUNNER_UPGRADE_REQUIRED 门控用） */
    boolean supports(String nodeId, int minVersion);

    /** PUSHING：会话节点需求工作区执行 git push（凭证随帧内存下发，runner 不落盘） */
    VerifyNodeResult push(String nodeId, VerifyPushCommand cmd, Consumer<String> sink);

    /** BUILDING：构建节点 verify 工作区 fetch/checkout + build_steps + manifest + zip 上传 */
    VerifyPackResult pack(String nodeId, VerifyPackCommand cmd, Consumer<String> sink);

    /** PATCHING：环境节点拉包 + manifest diff + 覆盖/删除 + pre/post 钩子（含恢复闭环） */
    VerifyPatchResult patch(String nodeId, VerifyPatchCommand cmd, Consumer<String> sink);

    /** 通用收口结果（工厂名避开记录访问器 ok() 冲突：success()/failed()） */
    record VerifyNodeResult(boolean ok, String error) {
        public static VerifyNodeResult success() { return new VerifyNodeResult(true, null); }
        public static VerifyNodeResult failed(String error) { return new VerifyNodeResult(false, error); }
    }

    /** PUSHING 指令：工作区按 runner 侧约定路径解析（服务端不知 runner workspaceRoot） */
    record VerifyPushCommand(String projectId, String workspaceOwner, String workspaceKey,
                             String remoteUrl, String branch, String repoToken, long timeoutSec) {
    }

    /** BUILDING 指令：steps 为 build_steps 快照；modules 原样透传 VERIFY_MODULES 环境变量 */
    record VerifyPackCommand(String projectId, String workspaceKey,
                             String remoteUrl, String branch, String commitSha, String repoToken,
                             List<BuildStep> steps, String outputDir, Map<String, String> env,
                             long runId, long timeoutSec, long bundleMaxBytes) {
    }

    /** 构建步骤快照（与 build_steps 表对应；command 已是完整脚本串） */
    record BuildStep(String name, String command, String workingDir) {
    }

    /** PATCHING 指令：manifestKey = <envId>-req<reqId>（runner 侧状态目录分键） */
    record VerifyPatchCommand(long runId, String patchDir, String preHook, String postHook,
                              String manifestKey, long timeoutSec) {
    }

    /** BUILDING 结果：fileCount = 产物树文件数（manifest 条目数） */
    record VerifyPackResult(boolean ok, String error, Integer fileCount) {
        public static VerifyPackResult failed(String error) { return new VerifyPackResult(false, error, null); }
    }

    /** PATCHING 结果：filesChanged = 本次下发（新增+变更+删除）文件数 */
    record VerifyPatchResult(boolean ok, String error, Integer filesChanged) {
        public static VerifyPatchResult failed(String error) { return new VerifyPatchResult(false, error, null); }
    }
}
