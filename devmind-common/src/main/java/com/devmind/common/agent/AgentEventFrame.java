package com.devmind.common.agent;

import java.util.Map;

/**
 * CAP-21 节点回传的已解析会话事件（事件解析下沉 runner，服务端对 CLI schema 无感）。
 * 字段与 devmind-session 的 SessionEvent 一一对应；seq 由服务端重编（runner seq 不带上行，
 * 单连接有序到达即可保证顺序）。
 *
 * @param sessionId 会话 ID
 * @param type      事件类型（system/assistant/tool_use/tool_result/text_delta/
 *                  permission_request/permission_result/result/error/log）
 * @param content   文本内容
 * @param source    stdout / stderr
 * @param timestamp runner 侧 epoch millis
 * @param payload   结构化负载
 */
public record AgentEventFrame(String sessionId, String type, String content, String source,
                              long timestamp, Map<String, Object> payload) {
}
