package com.devmind.build.service;

import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.build.dto.BuildView;
import com.devmind.build.model.BuildEntity;
import com.devmind.common.agent.plan.JobState;
import com.devmind.common.agent.plan.JobTerminalProbe;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 构建作业终态探针（CAP-76 FR-35）：BUILD kind。读模型复用 {@link BuildService#get(Long)}，
 * SecurityContext 在探测调用内合成（默认 THREADLOCAL 不跨线程；探针跑在计划虚拟线程上）。
 * e2e profile 下由 copilot 的假探针顶替（kind 唯一，避免真构建翻作业状态）。
 */
@Component
@Profile("!e2e")
public class BuildJobTerminalProbe implements JobTerminalProbe {

    private static final Logger log = LoggerFactory.getLogger(BuildJobTerminalProbe.class);

    private final BuildService builds;

    public BuildJobTerminalProbe(BuildService builds) {
        this.builds = builds;
    }

    @Override
    public String kind() {
        return "BUILD";
    }

    @Override
    public JobState probe(String entityId, String actor) {
        Long id = parseId(entityId);
        if (id == null) {
            return JobState.UNKNOWN;
        }
        BuildView view;
        try {
            view = withActor(actor, () -> builds.get(id));
        } catch (RuntimeException e) {
            // 记录不存在/不可判定 → UNKNOWN（调用方按 RUNNING 续等，交由超时兜底）
            log.debug("BUILD 探针读取失败: id={} err={}", entityId, e.toString());
            return JobState.UNKNOWN;
        }
        return switch (view.status()) {
            case BuildEntity.SUCCESS -> JobState.SUCCEEDED;
            case BuildEntity.FAILED -> JobState.FAILED;
            case BuildEntity.QUEUED, BuildEntity.RUNNING -> JobState.RUNNING;
            default -> JobState.UNKNOWN;
        };
    }

    @Override
    public String failureSummary(String entityId) {
        Long id = parseId(entityId);
        if (id == null) {
            return null;
        }
        try {
            BuildView view = builds.get(id);
            return view.errorSummary();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long parseId(String entityId) {
        try {
            return entityId == null ? null : Long.parseLong(entityId.trim());
        } catch (NumberFormatException e) {
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
