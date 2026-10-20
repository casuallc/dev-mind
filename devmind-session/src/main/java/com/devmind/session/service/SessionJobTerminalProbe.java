package com.devmind.session.service;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.agent.plan.JobState;
import com.devmind.common.agent.plan.JobTerminalProbe;
import com.devmind.common.agent.runtime.SessionState;
import com.devmind.session.dto.SessionView;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 会话作业终态探针（CAP-76 FR-35）：SESSION kind。读模型复用
 * {@link SessionManagerService#get(String)}；DONE=成功，FAILED/TERMINATED=失败，其余按进行中。
 */
@Component
public class SessionJobTerminalProbe implements JobTerminalProbe {

    private static final Logger log = LoggerFactory.getLogger(SessionJobTerminalProbe.class);

    private final SessionManagerService sessions;

    public SessionJobTerminalProbe(SessionManagerService sessions) {
        this.sessions = sessions;
    }

    @Override
    public String kind() {
        return "SESSION";
    }

    @Override
    public JobState probe(String entityId, String actor) {
        if (entityId == null || entityId.isBlank()) {
            return JobState.UNKNOWN;
        }
        SessionView view;
        try {
            view = withActor(actor, () -> sessions.get(entityId.trim()));
        } catch (RuntimeException e) {
            log.debug("SESSION 探针读取失败: id={} err={}", entityId, e.toString());
            return JobState.UNKNOWN;
        }
        SessionState state = view.state();
        if (state == SessionState.DONE) {
            return JobState.SUCCEEDED;
        }
        if (state == SessionState.FAILED || state == SessionState.TERMINATED) {
            return JobState.FAILED;
        }
        if (state == null) {
            return JobState.UNKNOWN;
        }
        return JobState.RUNNING;
    }

    @Override
    public String failureSummary(String entityId) {
        try {
            SessionView view = sessions.get(entityId.trim());
            return view.summary();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> T withActor(String actor, java.util.function.Supplier<T> body) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new DevMindPrincipal(actor, "VIEWER"), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return body.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
