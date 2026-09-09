package com.devmind.session.service;

import com.devmind.common.agent.AgentNodeSessionsProvider;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.session.repo.SessionRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-21 FR-09：向 agent 模块暴露「指定节点上的活跃开发会话」（强制升级前展示用）。
 * 活动三态与 {@link SessionState#isActive()} 对齐。
 */
@Component
public class SessionNodeSessionsProvider implements AgentNodeSessionsProvider {

    private static final List<String> ACTIVE_STATUSES = List.of(
            SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(), SessionState.WAITING_AUTH.name());

    private final SessionRepository repo;

    public SessionNodeSessionsProvider(SessionRepository repo) {
        this.repo = repo;
    }

    @Override
    public List<ActiveSessionInfo> activeSessions(String nodeId) {
        return repo.findByAgentNodeIdAndStatusIn(nodeId, ACTIVE_STATUSES).stream()
                .map(e -> new ActiveSessionInfo(e.getId(), "SESSION", titleOf(e), e.getStatus(),
                        e.getCreatedBy(), e.getCreatedAt()))
                .toList();
    }

    /** 列表标题：summary 优先，空则 taskSpec 首行截断。 */
    private static String titleOf(com.devmind.session.model.SessionEntity e) {
        if (e.getSummary() != null && !e.getSummary().isBlank()) {
            return e.getSummary();
        }
        String spec = e.getTaskSpec() == null ? "" : e.getTaskSpec().strip();
        int nl = spec.indexOf('\n');
        String first = nl >= 0 ? spec.substring(0, nl) : spec;
        return first.length() > 60 ? first.substring(0, 60) + "…" : first;
    }
}
