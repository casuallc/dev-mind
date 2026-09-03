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
 */
public record AgentLaunchCommand(String sessionId, String projectId, String taskSpec,
                                 String model, String permissionMode, Map<String, String> env) {
}
