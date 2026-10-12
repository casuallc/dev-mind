package com.devmind.worklog.dto;

import java.time.Instant;

/**
 * CAP-74：工作日志空间视图改服务端仓口径——仓是事实源（repoKey/remoteUrl/remoteBranch/
 * lastCommitAt），WORKLOG 项目与亲和节点只用于「AI 生成/对话」链路（离线不影响读/编辑/推送）。
 *
 * @param exists       是否已初始化（仓或项目任一存在；false 时其余字段为 null，前端引导调 ensure）
 * @param repoKey      活动仓键（写入路由后的仓；个人仓 personal-<username>）
 * @param remoteUrl    个人仓绑定的远端备份 URL（null = 未绑定）
 * @param remoteBranch 远端目标分支
 * @param lastCommitAt 活动仓 HEAD 最近提交时间（null = 空仓/未知）
 * @param fetchStale   读路径后台 fetch 失败/冲突降级（fail-visible 提示；null/false = 正常或未绑远端）
 * @param projectId    WORKLOG 项目 id（null = 未建——无默认节点时仓先行、项目降级缺位）
 * @param agentNodeId  亲和节点 id（创建时固化，不可改）
 * @param nodeOnline   亲和节点当前是否在线（null = 未知/无节点；离线仅影响 AI 生成与对话）
 */
public record WorkspaceView(boolean exists, String repoKey, String remoteUrl, String remoteBranch,
                            Instant lastCommitAt, Boolean fetchStale,
                            String projectId, String agentNodeId, Boolean nodeOnline) {

    public static WorkspaceView absent() {
        return new WorkspaceView(false, null, null, null, null, null, null, null, null);
    }
}
