package com.devmind.common.agent;

import java.util.Map;

/**
 * CAP-65 file_ack 应答：runner 对 file 帧的回执。
 *
 * @param ok      操作是否成功（路径越界/白名单外 root/二进制文件/目标已存在等 → false + error）
 * @param payload 结果负载（op 各异：list=entries[]+truncated，read=content+size，
 *                download=size+sha256，其余通常仅 size；可为 null）
 * @param error   失败原因（用户可读）
 */
public record AgentFileResult(boolean ok, Map<String, Object> payload, String error) {

    public static AgentFileResult ok(Map<String, Object> payload) {
        return new AgentFileResult(true, payload, null);
    }

    public static AgentFileResult failed(String error) {
        return new AgentFileResult(false, null, error);
    }
}
