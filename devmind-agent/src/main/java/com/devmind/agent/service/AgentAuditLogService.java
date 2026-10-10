package com.devmind.agent.service;

import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.repo.AgentAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * CAP-71 节点审计日志：agent 节点域全量交互旁路落库（连接/隧道/指令/代理/上下文包/
 * 文件中转/规则/包/节点运维九分类），供「Agent 执行 → 审计日志」页检索。
 *
 * <p>写入纪律：同步 {@code repo.save} 整体 try-catch——审计失败只降级 log.warn，
 * 绝不阻塞 WS 派发线程与隧道 relay 线程主流程（量级与连接流水同级，不引队列）。</p>
 */
@Service
public class AgentAuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AgentAuditLogService.class);
    /** detail 摘要上限（与列长一致，超长截断防 500） */
    private static final int DETAIL_MAX = 500;
    /** 列表接口 size 上限（与 /api/audit-logs 同约定） */
    public static final int MAX_PAGE_SIZE = 200;

    private final AgentAuditLogRepository repo;

    public AgentAuditLogService(AgentAuditLogRepository repo) {
        this.repo = repo;
    }

    /**
     * 一条审计记录。建造器风格：
     * {@code record(Entry.of(CAT_PROXY, "OPEN").node(node).target("h:443").result(OK)...)}。
     */
    public record Entry(String category, String event, Long nodeId, String nodeName, String actor,
                        String remoteAddr, String target, String result,
                        Long bytesUp, Long bytesDown, Long durationMs, String detail) {

        public static Entry of(String category, String event) {
            return new Entry(category, event, null, null, null, null, null, null, null, null, null, null);
        }

        /** 关联节点（id + 名字快照） */
        public Entry node(AgentNodeEntity node) {
            return node == null ? this
                    : new Entry(category, event, node.getId(), node.getName(), actor,
                            remoteAddr, target, result, bytesUp, bytesDown, durationMs, detail);
        }

        /** 只带节点 id（节点名快照由调用方补，或接受为空——如代理拒绝时规则节点已删） */
        public Entry nodeId(Long id) {
            return new Entry(category, event, id, nodeName, actor,
                    remoteAddr, target, result, bytesUp, bytesDown, durationMs, detail);
        }

        public Entry nodeName(String name) {
            return new Entry(category, event, nodeId, name, actor,
                    remoteAddr, target, result, bytesUp, bytesDown, durationMs, detail);
        }

        public Entry actor(String v) {
            return new Entry(category, event, nodeId, nodeName, v,
                    remoteAddr, target, result, bytesUp, bytesDown, durationMs, detail);
        }

        public Entry remoteAddr(String v) {
            return new Entry(category, event, nodeId, nodeName, actor,
                    v, target, result, bytesUp, bytesDown, durationMs, detail);
        }

        public Entry target(String v) {
            return new Entry(category, event, nodeId, nodeName, actor,
                    remoteAddr, v, result, bytesUp, bytesDown, durationMs, detail);
        }

        public Entry result(String v) {
            return new Entry(category, event, nodeId, nodeName, actor,
                    remoteAddr, target, v, bytesUp, bytesDown, durationMs, detail);
        }

        public Entry bytes(long up, long down) {
            return new Entry(category, event, nodeId, nodeName, actor,
                    remoteAddr, target, result, up, down, durationMs, detail);
        }

        public Entry durationMs(long v) {
            return new Entry(category, event, nodeId, nodeName, actor,
                    remoteAddr, target, result, bytesUp, bytesDown, v, detail);
        }

        public Entry detail(String v) {
            return new Entry(category, event, nodeId, nodeName, actor,
                    remoteAddr, target, result, bytesUp, bytesDown, durationMs, v);
        }
    }

    /** 旁路落库：任何失败只 log.warn，不打断主流程。 */
    public void record(Entry entry) {
        try {
            AgentAuditLogEntity e = new AgentAuditLogEntity();
            e.setCreatedAt(Instant.now());
            e.setCategory(entry.category());
            e.setEvent(entry.event());
            e.setNodeId(entry.nodeId());
            e.setNodeName(entry.nodeName());
            e.setActor(entry.actor());
            e.setRemoteAddr(entry.remoteAddr());
            e.setTarget(truncate(entry.target(), 255));
            e.setResult(entry.result());
            e.setBytesUp(entry.bytesUp());
            e.setBytesDown(entry.bytesDown());
            e.setDurationMs(entry.durationMs());
            e.setDetail(truncate(entry.detail(), DETAIL_MAX));
            repo.save(e);
        } catch (Exception ex) {
            log.warn("审计日志落库失败（已旁路降级）: category={} event={} err={}",
                    entry.category(), entry.event(), ex.toString());
        }
    }

    /** 组合检索（条件均可空）；page 从 0 起，size 钳制 [1, MAX_PAGE_SIZE]。 */
    public Page<AgentAuditLogEntity> search(String category, String event, Long nodeId, String result,
                                            String keyword, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return repo.search(blankToNull(category), blankToNull(event), nodeId,
                blankToNull(result), blankToNull(keyword), PageRequest.of(p, s));
    }

    /** 按保留期硬删（定时任务调用），返回删除数。 */
    public long purge(int retentionDays) {
        long deleted = repo.deleteByCreatedAtBefore(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("节点审计日志清理: 删除 {} 条（保留 {} 天）", deleted, retentionDays);
        }
        return deleted;
    }

    /** WS 远端地址格式化为 "IP:端口"（InetSocketAddress#toString 带前导斜杠）。 */
    public static String formatRemoteAddr(InetSocketAddress addr) {
        if (addr == null) {
            return null;
        }
        String host = addr.getAddress() != null ? addr.getAddress().getHostAddress() : addr.getHostString();
        return host + ":" + addr.getPort();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
