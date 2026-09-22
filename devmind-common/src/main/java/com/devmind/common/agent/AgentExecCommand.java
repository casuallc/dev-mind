package com.devmind.common.agent;

import java.util.Map;

/**
 * CAP-36 exec 帧模型（构建/测试/部署/发版下发 runner 执行，协议 v3 起）。
 *
 * <p>command 为<b>服务端渲染后的完整脚本串</b>（模板/参数渲染只发生在服务端，runner 不感知模板——
 * 延续 CAP-34「数据所有权在服务端」）；runner 写临时 .sh 后以配置的 execShell 执行
 * （与服务端 LocalStepRunner 同款形态），stdout/stderr 行流式回 exec_log 帧，退出码 exec_exit 收口。</p>
 *
 * @param execId      本次执行唯一 id（帧路由键，[a-zA-Z0-9._-]）
 * @param projectId   项目 id（构建工作区归目录属 + runner 项目映射回退用；可空 = 无项目上下文）
 * @param workspaceId 同一条执行链共享的工作区 id（如 build-&lt;id&gt;；带 repo 时必填，
 *                    同 workspaceId 的后续步骤复用已备好的工作区，不再 fetch/checkout）
 * @param command     渲染后的完整脚本串
 * @param workingDir  相对工作目录（相对构建工作区 / runner 项目映射目录解析；可空 = 工作区根）
 * @param env         注入子进程的环境变量（可空）
 * @param timeoutSec  单步超时（秒），超时 runner 整树 kill 后回 timedOut=true
 * @param repo        构建工作区描述（可空 = 不准备代码，直接在 runner 本地目录执行——部署/发版场景）
 * @param bundle      CAP-56 执行包引用（可空 = 普通脚本步骤），见 {@link LabBundleRef}
 */
public record AgentExecCommand(String execId, String projectId, String workspaceId, String command,
                               String workingDir, Map<String, String> env, long timeoutSec, Repo repo,
                               LabBundleRef bundle) {

    /** 兼容构造（CAP-56 前的 8 参签名）：普通脚本步骤，无执行包 */
    public AgentExecCommand(String execId, String projectId, String workspaceId, String command,
                            String workingDir, Map<String, String> env, long timeoutSec, Repo repo) {
        this(execId, projectId, workspaceId, command, workingDir, env, timeoutSec, repo, null);
    }

    /**
     * 构建工作区 repo 块（语义照搬 launch 帧 RepoSpec，CAP-25）：
     * runner 侧克隆缓存 &lt;workspaceRoot&gt;/&lt;projectId&gt;/main 复用，构建工作区
     * &lt;projectId&gt;/builds/&lt;workspaceId&gt; detach checkout 到 commit，无分支无 push，
     * 结束即弃（超龄 GC 兜底）。
     *
     * @param commit 检出基准（空 = branch 的 FETCH_HEAD，再空 = HEAD）
     * @param token  git 凭据（CloneTokenResolver 解析；仅随帧传输 + runner 内存持有，不落盘）
     */
    public record Repo(String remoteUrl, String branch, String commit, String token) {
    }

    /**
     * CAP-56 执行包引用（帧里只放引用，字节由 runner 凭节点 token 走 HTTP 拉取）。
     *
     * <p>runner 侧动作：{@code GET /api/agent/decision-lab/bundles/{kind}/{id}?token=} → 解到临时目录
     * → env 注入 {@code DEVMIND_LAB_SCRIPT}（清单里的入口脚本绝对路径）与
     * {@code DEVMIND_LAB_PAYLOAD}（数据文件绝对路径）→ 跑 command；<b>拉取失败即 exec 失败</b>
     * （不降级跑一个没有脚本与数据的步骤，同 CAP-34 上下文包口径）。</p>
     *
     * @param kind 任务类型（{@code evaluation} / {@code finetune}；见 common 的
     *             {@code DecisionLabBundleProvider} 常量——这里用字符串以免 agent 模块反向依赖）
     * @param id   任务 id
     */
    public record LabBundleRef(String kind, String id) {
    }
}
