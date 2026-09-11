package com.devmind.common.agent;

import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.exec.ContextPackage;

import java.util.List;
import java.util.Map;

/**
 * CAP-21 远程会话启动指令（服务端 → 节点 runner）。
 *
 * @param sessionId      会话 ID（服务端分配，runner 只做透传回传）
 * @param projectId      项目 ID（runner 据此查本地路径映射得 workdir；空 = runner 默认工作目录）
 * @param taskSpec       任务说明（作为初始 prompt 写入 agent stdin）
 * @param model          模型（可空 = runner/CLI 默认）
 * @param permissionMode 权限模式
 * @param env            CAP-24：附加进程环境变量（GIT_AUTHOR_NAME 等提交身份变量）；
 *                       null/空 = 不附加。旧版 runner 忽略该字段（优雅降级为系统 git 配置身份）
 * @param repo           CAP-25：远程工作区描述（runner 据此 clone/fetch/切会话分支/结束 push）；
 *                       null = 旧行为（workdir 走节点 project.<id> 映射/兜底目录，代码节点自理）。
 *                       旧版 runner 逐字段读帧，未知 repo 块天然忽略。
 * @param kind           CAP-30：会话种类——"session"（缺省，项目开发会话）/ "chat"（通用问答，
 *                       runner 用 &lt;workspaceRoot&gt;/_chat/&lt;sid&gt; 沙箱目录，无 clone/push）。
 *                       旧版 runner 忽略该字段：chat 帧退化为兜底 workDir（部署侧需升级 runner）。
 * @param repos          CAP-31：多仓库工作区描述（每项含 name）。null/空 = 单库（看 repo 字段）。
 *                       旧版 runner 只读 repo 单字段 → 降级为只拉主库。
 * @param contextManifest CAP-34：上下文包清单（{@link ContextPackage} 的 sha256/大小/条目数）。
 *                       runner 据此 HTTP 拉包物化后再拉起 claude；null = 无上下文。
 *                       旧版 runner 忽略该字段（优雅降级为无上下文会话）。
 * @param resumeSessionId 续接目标 CLI 会话 id（claude --resume，服务端 resume 时带上）；
 *                       null/空 = 全新对话。对话历史在 runner 侧 CLI 配置目录按 cwd 归档，
 *                       进程退出不丢失；id 失效（配置目录已清理）时 CLI 报错退出即 launch 失败。
 *                       旧版 runner 忽略该字段（优雅降级为无对话历史的全新会话）。
 */
public record AgentLaunchCommand(String sessionId, String projectId, String taskSpec,
                                 String model, String permissionMode, Map<String, String> env,
                                 RepoSpec repo, String kind, List<RepoSpec> repos,
                                 ContextManifest contextManifest, String resumeSessionId) {

    /** 兼容构造器：CAP-25 及之前的调用点（kind=session，无多库、无上下文）。 */
    public AgentLaunchCommand(String sessionId, String projectId, String taskSpec,
                              String model, String permissionMode, Map<String, String> env,
                              RepoSpec repo) {
        this(sessionId, projectId, taskSpec, model, permissionMode, env, repo, "session", null, null, null);
    }

    /** 兼容构造器：CAP-30/31 调用点（无上下文清单）。 */
    public AgentLaunchCommand(String sessionId, String projectId, String taskSpec,
                              String model, String permissionMode, Map<String, String> env,
                              RepoSpec repo, String kind, List<RepoSpec> repos) {
        this(sessionId, projectId, taskSpec, model, permissionMode, env, repo, kind, repos, null, null);
    }

    /** 兼容构造器：CAP-34 调用点（无续接，全新对话）。 */
    public AgentLaunchCommand(String sessionId, String projectId, String taskSpec,
                              String model, String permissionMode, Map<String, String> env,
                              RepoSpec repo, String kind, List<RepoSpec> repos,
                              ContextManifest contextManifest) {
        this(sessionId, projectId, taskSpec, model, permissionMode, env, repo, kind, repos,
                contextManifest, null);
    }

    /**
     * CAP-25 远程工作区描述。token 为短期凭据（仅随帧传输 + runner 内存持有，不落盘）。
     *
     * @param remoteUrl  仓库远端地址（http/https）
     * @param baseBranch 基线分支（fetch 目标）
     * @param branch     会话分支（服务端按 feature/&lt;sessionId&gt; 约定生成，runner 不复制命名逻辑）
     * @param token      git 凭据（CAP-24 优先级：会话发起人个人 PAT → 项目绑定 Integration）
     * @param name       CAP-31：仓库名（多库时作子目录名；单库兼容构造器为 null）
     */
    public record RepoSpec(String remoteUrl, String baseBranch, String branch, String token, String name) {

        /** 兼容构造器：单库（无 name）。 */
        public RepoSpec(String remoteUrl, String baseBranch, String branch, String token) {
            this(remoteUrl, baseBranch, branch, token, null);
        }
    }
}
