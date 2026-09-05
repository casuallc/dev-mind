package com.devmind.common.agent;

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
 */
public record AgentLaunchCommand(String sessionId, String projectId, String taskSpec,
                                 String model, String permissionMode, Map<String, String> env,
                                 RepoSpec repo) {

    /**
     * CAP-25 远程工作区描述。token 为短期凭据（仅随帧传输 + runner 内存持有，不落盘）。
     *
     * @param remoteUrl  主库远端地址（http/https）
     * @param baseBranch 基线分支（fetch 目标）
     * @param branch     会话分支（服务端按 feature/&lt;sessionId&gt; 约定生成，runner 不复制命名逻辑）
     * @param token      git 凭据（CAP-24 优先级：会话发起人个人 PAT → 项目绑定 Integration）
     */
    public record RepoSpec(String remoteUrl, String baseBranch, String branch, String token) {
    }
}
