package com.devmind.common.agent;

import java.util.List;
import java.util.Map;

/**
 * CAP-73 需求开发中验证帧模型（协议 v22 起）：验证链三阶段的下行指令——
 * verify_push（会话节点推送需求分支）/ verify_pack（构建节点构建+打产物包+HTTP 上传）/
 * verify_patch（环境节点拉包+manifest 差量补丁+钩子）。上行统一 verify_log（进度行）+
 * verify_ack（收口，{@link AgentVerifyResult}）。
 *
 * <p>共同红线：
 * <ul>
 *   <li>token（git 凭据）仅随帧传输 + runner 内存持有，不落盘不进日志（同 launch/exec repo 块）；</li>
 *   <li>产物包字节不入帧——runner 凭自身节点 token 走 HTTP 上下行
 *       （POST/GET /api/agent/verify/runs/{runId}/bundle，serverUrl 取自 runner 自身配置）；</li>
 *   <li>{@code requestId} 为帧路由键（[a-zA-Z0-9._-]），三类指令共用 verify_ack 收口。</li>
 * </ul>
 */
public final class AgentVerifyCommand {

    private AgentVerifyCommand() {
    }

    /**
     * PUSHING：会话节点需求工作区 {@code <workspaceRoot>/<projectId>/<workspaceOwner>/worktrees/<workspaceKey>}
     * 执行 git push（{@code push <url> <branch>:<branch>}，token 显式注入不落 .git/config）。
     * 工作区不存在/无提交不抛——runner 回 ok=false 附原因，编排端按失败收口。
     */
    public record Push(String requestId, long runId, String projectId, String workspaceOwner,
                       String workspaceKey, String remoteUrl, String branch, String token,
                       long timeoutSec) {
    }

    /**
     * BUILDING：构建节点在<b>可复用 verify 工作区</b>
     * {@code <workspaceRoot>/<projectId>/verify/<workspaceKey>}（持久克隆缓存，增量复用）内
     * fetch + checkout 到 commitSha，顺序跑 build_steps（workingDir 相对该工作区根解析），
     * 对 outputDir 产物树逐文件 sha256 生成 manifest，整树打 zip 经 HTTP 上传
     * （超限 bundleMaxBytes 直接失败）。env 原样注入子进程（VERIFY_MODULES 等 AI 提示透传）。
     */
    public record Pack(String requestId, long runId, String projectId, String workspaceKey,
                       String remoteUrl, String branch, String commitSha, String token,
                       List<Step> steps, String outputDir, Map<String, String> env,
                       long timeoutSec, long bundleMaxBytes) {

        /** 构建步骤快照（command 为服务端渲染后的完整脚本串，runner 不感知模板） */
        public record Step(String name, String command, String workingDir) {
        }
    }

    /**
     * PATCHING：环境节点经 HTTP 拉取 run 产物包 → 解到 staging → 与本机 manifest
     * （{@code verify-manifests/<manifestKey>.json}，按需求键控防跨需求误删）逐文件 sha256
     * diff → 覆盖/新增/删除落 patchDir → pre/post 钩子（pre 成功后任一后续失败仍必跑 post
     * 恢复闭环）。patchDir 绝对路径由 runner 侧环境变量白名单/根目录约束防越权
     * （服务端只做非空校验）。
     */
    public record Patch(String requestId, long runId, String patchDir, String preHook,
                        String postHook, String manifestKey, long timeoutSec) {
    }
}
