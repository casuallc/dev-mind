package com.devmind.copilot.service;

import com.devmind.common.agent.plan.JobTerminalProbe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 作业终态探针注册表（CAP-76 FR-35）：启动期收集全部 {@link JobTerminalProbe} 按 kind 索引，
 * kind 重复 fail-fast（启动即炸，不留到运行期猜）。探针由各能力模块随模块交付，
 * copilot 不依赖业务模块。
 */
@Component
public class PlanProbes {

    private final Map<String, JobTerminalProbe> byKind;

    public PlanProbes(ObjectProvider<JobTerminalProbe> provider) {
        Map<String, JobTerminalProbe> map = new LinkedHashMap<>();
        provider.forEach(probe -> {
            JobTerminalProbe prev = map.put(probe.kind(), probe);
            if (prev != null) {
                throw new IllegalStateException("JobTerminalProbe kind 重复：" + probe.kind()
                        + "（" + prev.getClass().getName() + " / " + probe.getClass().getName() + "）");
            }
        });
        this.byKind = Map.copyOf(map);
    }

    public Optional<JobTerminalProbe> find(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }
}
