package com.devmind.session.service;

import com.devmind.project.WorkItemSessionLookup;
import com.devmind.session.dto.SessionView;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CAP-76：工作项关联会话（WorkItemSessionLookup 实现），供 project 域工具展示关联会话。
 */
@Component
public class SessionWorkItemLookup implements WorkItemSessionLookup {

    private final SessionManagerService sessions;

    public SessionWorkItemLookup(SessionManagerService sessions) {
        this.sessions = sessions;
    }

    @Override
    public List<SessionBrief> sessionsFor(String workItemId) {
        if (workItemId == null || workItemId.isBlank()) {
            return List.of();
        }
        return sessions.list(null, null, workItemId, null).stream()
                .map(SessionWorkItemLookup::brief)
                .toList();
    }

    private static SessionBrief brief(SessionView s) {
        return new SessionBrief(s.id(), s.status(), s.taskSpec(), s.createdAt());
    }
}
