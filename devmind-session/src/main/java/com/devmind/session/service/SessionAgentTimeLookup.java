package com.devmind.session.service;

import com.devmind.project.RequirementAgentTimeLookup;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionState;
import com.devmind.session.repo.SessionRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-27 AI 实际耗时汇总（RequirementAgentTimeLookup 实现）：
 * 终态会话（finishedAt≠null）按 finishedAt-createdAt；活跃会话（SessionState.isActive）
 * 按 now-createdAt 算到当前时刻；SUSPENDED 等无 finishedAt 的非活跃会话跳过（挂起时长不计）。
 */
@Component
public class SessionAgentTimeLookup implements RequirementAgentTimeLookup {

    private final SessionRepository sessionRepo;

    public SessionAgentTimeLookup(SessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    @Override
    public Map<String, Long> secondsFor(Collection<String> requirementIds) {
        if (requirementIds == null || requirementIds.isEmpty()) {
            return Map.of();
        }
        Instant now = Instant.now();
        Map<String, Long> out = new HashMap<>();
        List<SessionEntity> sessions = sessionRepo.findByRequirementIdIn(requirementIds);
        for (SessionEntity s : sessions) {
            if (s.getRequirementId() == null || s.getCreatedAt() == null) {
                continue;
            }
            Long seconds = durationSeconds(s, now);
            if (seconds != null && seconds > 0) {
                out.merge(s.getRequirementId(), seconds, Long::sum);
            }
        }
        return out;
    }

    /** 单会话有效时长（秒）：终态按 finished-created，活跃算到 now，其他 null（不计） */
    static Long durationSeconds(SessionEntity s, Instant now) {
        if (s.getFinishedAt() != null) {
            return Duration.between(s.getCreatedAt(), s.getFinishedAt()).getSeconds();
        }
        if (s.getStatus() != null) {
            try {
                if (SessionState.valueOf(s.getStatus()).isActive()) {
                    return Duration.between(s.getCreatedAt(), now).getSeconds();
                }
            } catch (IllegalArgumentException ignored) {
                // 未知状态值不计
            }
        }
        return null;
    }
}
