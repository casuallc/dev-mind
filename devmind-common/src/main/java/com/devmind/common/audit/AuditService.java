package com.devmind.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 全局审计服务（P0-3）：任何域经 {@link #record} 写一条操作留痕。
 * 审计是旁路：写失败只记日志，不打断主流程。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /**
     * @param domain  域（server/build/deploy/test/task/…）
     * @param action  动作（trigger/confirm/execute/…）
     * @param actor   操作者；null 记为 system
     * @param detail  结果摘要（截断留存）
     */
    public AuditLogEntity record(String domain, String action, String actor, String projectId,
                                 Boolean success, String detail) {
        try {
            AuditLogEntity a = new AuditLogEntity();
            a.setDomain(domain);
            a.setAction(action);
            a.setActor(actor == null || actor.isBlank() ? "system" : actor);
            a.setProjectId(projectId);
            a.setSuccess(success);
            a.setDetail(truncate(detail, 4000));
            a.setCreatedAt(Instant.now());
            return repo.save(a);
        } catch (Exception e) {
            log.warn("审计写入失败: domain={} action={} err={}", domain, action, e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }
}
