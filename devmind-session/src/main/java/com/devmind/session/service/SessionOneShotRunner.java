package com.devmind.session.service;

import com.devmind.common.agent.OneShotAgentRunner;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.session.config.SessionProperties;
import com.devmind.session.dto.CreateSessionRequest;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionState;
import com.devmind.session.repo.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * CAP-28 {@link OneShotAgentRunner} 实现：复用会话生命周期的「无项目裸跑」链路
 * （不建 worktree、无知识注入），create 后立即 finish 关 stdin——claude print 模式
 * 每回合结束发 result 后不退出、等 stdin，只有 EOF 才退出，退出时 onExit 把 result
 * 写入 {@link SessionEntity#setSummary}。
 *
 * <p>权限模式走专用配置 {@code devmind.session.oneshot-permission-mode}（默认 plan，
 * 只读总结任务，不复用全局 acceptEdits）；调度线程无 SecurityContext 时
 * createdBy 回退 local（归属由调用方业务表自行记录，会话仅作执行痕迹）。</p>
 */
@Service
public class SessionOneShotRunner implements OneShotAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SessionOneShotRunner.class);

    private static final Set<String> TERMINAL = Set.of(
            SessionState.DONE.name(), SessionState.FAILED.name(), SessionState.TERMINATED.name());

    private final SessionManagerService sessions;
    private final SessionRepository sessionRepo;
    private final SessionProperties props;

    public SessionOneShotRunner(SessionManagerService sessions,
                                SessionRepository sessionRepo,
                                SessionProperties props) {
        this.sessions = sessions;
        this.sessionRepo = sessionRepo;
        this.props = props;
    }

    @Override
    public Result run(String prompt, int timeoutSeconds) {
        // 无 projectId/workItemId 裸跑；permissionMode 显式传 one-shot 专用值
        String sessionId = sessions.create(new CreateSessionRequest(
                null, null, null, null, prompt, null, null,
                props.getOneshotPermissionMode(), null)).id();
        try {
            sessions.finish(sessionId);
        } catch (Exception e) {
            log.warn("one-shot 关 stdin 失败，尝试直接终止: session={} err={}", sessionId, e.getMessage());
            safeKill(sessionId);
            throw new DevMindException(ErrorCode.INTERNAL, "one-shot 会话结束信号失败: " + e.getMessage(), e);
        }

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            SessionEntity ent = sessionRepo.findById(sessionId).orElse(null);
            if (ent != null && TERMINAL.contains(ent.getStatus())) {
                if (SessionState.DONE.name().equals(ent.getStatus())
                        && ent.getSummary() != null && !ent.getSummary().isBlank()) {
                    return new Result(sessionId, ent.getSummary());
                }
                throw new DevMindException(ErrorCode.INTERNAL,
                        "one-shot 会话未产出结果: status=" + ent.getStatus()
                                + (ent.getSummary() != null ? " summary=" + ent.getSummary() : ""));
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                safeKill(sessionId);
                throw new DevMindException(ErrorCode.INTERNAL, "one-shot 等待被中断", e);
            }
        }
        safeKill(sessionId);
        throw new DevMindException(ErrorCode.INTERNAL,
                "one-shot 会话超时(" + timeoutSeconds + "s)已终止: " + sessionId);
    }

    private void safeKill(String sessionId) {
        try {
            sessions.kill(sessionId);
        } catch (Exception e) {
            log.debug("one-shot 终止会话（可能已退出）: session={} err={}", sessionId, e.getMessage());
        }
    }
}
