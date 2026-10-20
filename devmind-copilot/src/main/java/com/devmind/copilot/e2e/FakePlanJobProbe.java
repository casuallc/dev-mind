package com.devmind.copilot.e2e;

import com.devmind.common.agent.plan.JobState;
import com.devmind.common.agent.plan.JobTerminalProbe;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * E2E 假作业终态探针（CAP-76 场景 12，仅 e2e profile 装配）：kind=BUILD 顶替真探针，
 * 不触碰真构建。状态表在内存里，由 {@link FakePlanJobController} 翻转；
 * 未知 id 一律按 RUNNING（排队中）——计划 await 会停在 WAITING 直到被翻转或超时。
 */
@Component
@Profile("e2e")
public class FakePlanJobProbe implements JobTerminalProbe {

    private final Map<String, JobState> states = new ConcurrentHashMap<>();

    @Override
    public String kind() {
        return "BUILD";
    }

    @Override
    public JobState probe(String entityId, String actor) {
        return states.getOrDefault(entityId, JobState.RUNNING);
    }

    @Override
    public String failureSummary(String entityId) {
        return states.get(entityId) == JobState.FAILED ? "e2e 假作业被置为失败" : null;
    }

    public void setState(String entityId, JobState state) {
        if (entityId != null && state != null) {
            states.put(entityId, state);
        }
    }

    public Map<String, JobState> states() {
        return Map.copyOf(states);
    }

    public void reset() {
        states.clear();
    }
}
