package com.devmind.agent.tunnel;

import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.service.AgentAuditLogService;

/**
 * CAP-71：代理（PROXY 分类）审计写入助手，SOCKS5 与 HTTP 代理两端点共用。
 * 拒绝路径记 REJECTED_NO_RULE / FAIL_UNAVAILABLE；成功路径由流终结回调记 OPEN
 * （恰好一条，result=OK 正常双向收尾 / FAIL 中止，含字节数与耗时）。
 */
final class ProxyAudit {

    private ProxyAudit() { }

    /** 拒绝/失败路径：未命中规则（nodeId 空，REJECTED）或命中但隧道不可用（FAIL）。 */
    static void reject(AgentAuditLogService audit, String event, Long nodeId,
                       String host, int port, String proto, String detail) {
        audit.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_PROXY, event)
                .nodeId(nodeId).target(host + ":" + port)
                .result(event.startsWith("REJECTED")
                        ? AgentAuditLogEntity.RESULT_REJECTED : AgentAuditLogEntity.RESULT_FAIL)
                .detail(proto + (detail == null ? "" : " | " + detail)));
    }

    /** 流终结回调：恰好一条 OPEN，带上下行字节与耗时。 */
    static void streamClosed(AgentAuditLogService audit, Long nodeId, String host, int port,
                             String proto, ServerTunnelStream.StreamSummary s) {
        boolean ok = s.reason() == null;
        audit.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_PROXY, "OPEN")
                .nodeId(nodeId).target(host + ":" + port)
                .result(ok ? AgentAuditLogEntity.RESULT_OK : AgentAuditLogEntity.RESULT_FAIL)
                .bytes(s.bytesUp(), s.bytesDown()).durationMs(s.durationMs())
                .detail(proto + (ok ? "" : " | " + s.reason())));
    }
}
