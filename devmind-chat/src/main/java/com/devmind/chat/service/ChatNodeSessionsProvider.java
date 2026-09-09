package com.devmind.chat.service;

import com.devmind.common.agent.AgentNodeSessionsProvider;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.chat.repo.ChatSessionRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CAP-21 FR-09：向 agent 模块暴露「指定节点上的活跃问答」（强制升级前展示用）。
 * 活动三态与 {@link SessionState#isActive()} 对齐。
 */
@Component
public class ChatNodeSessionsProvider implements AgentNodeSessionsProvider {

    private static final List<String> ACTIVE_STATUSES = List.of(
            SessionState.RUNNING.name(), SessionState.WAITING_INPUT.name(), SessionState.WAITING_AUTH.name());

    private final ChatSessionRepository repo;

    public ChatNodeSessionsProvider(ChatSessionRepository repo) {
        this.repo = repo;
    }

    @Override
    public List<ActiveSessionInfo> activeSessions(String nodeId) {
        return repo.findByAgentNodeIdAndStatusIn(nodeId, ACTIVE_STATUSES).stream()
                .map(e -> new ActiveSessionInfo(e.getId(), "CHAT", e.getTitle(), e.getStatus(),
                        e.getCreatedBy(), e.getCreatedAt()))
                .toList();
    }
}
