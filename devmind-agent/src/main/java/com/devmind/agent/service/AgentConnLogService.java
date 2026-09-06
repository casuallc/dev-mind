package com.devmind.agent.service;

import com.devmind.agent.model.AgentConnLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.repo.AgentConnLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * CAP-21 节点连接流水：接入/拒绝/断线落库，供 Agent 节点页「连接日志」排查
 * （原来只能翻服务端日志文件）。保留 7 天，每日清理。
 */
@Service
public class AgentConnLogService {

    private static final Logger log = LoggerFactory.getLogger(AgentConnLogService.class);
    /** 列表接口单次返回上限 */
    private static final int MAX_LIMIT = 500;

    private final AgentConnLogRepository repo;

    public AgentConnLogService(AgentConnLogRepository repo) {
        this.repo = repo;
    }

    /** 记一条流水（node 可空：拒绝时 token 解析不出节点）。 */
    public void record(String event, AgentNodeEntity node, String remoteAddr, String detail) {
        AgentConnLogEntity e = new AgentConnLogEntity();
        e.setEvent(event);
        if (node != null) {
            e.setNodeId(node.getId());
            e.setNodeName(node.getName());
        }
        e.setRemoteAddr(remoteAddr);
        e.setDetail(detail);
        e.setCreatedAt(Instant.now());
        repo.save(e);
    }

    /** 最新 N 条（倒序）。 */
    public List<AgentConnLogEntity> latest(int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return repo.findByOrderByCreatedAtDescIdDesc(PageRequest.of(0, size));
    }

    /** WS 远端地址格式化为 "IP:端口"（InetSocketAddress#toString 带前导斜杠）。 */
    public static String formatRemoteAddr(java.net.InetSocketAddress addr) {
        if (addr == null) {
            return null;
        }
        String host = addr.getAddress() != null ? addr.getAddress().getHostAddress() : addr.getHostString();
        return host + ":" + addr.getPort();
    }

    /** 保留 7 天，每日凌晨清理。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpired() {
        long deleted = repo.deleteByCreatedAtBefore(Instant.now().minus(7, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("节点连接日志清理: 删除 {} 条", deleted);
        }
    }
}
