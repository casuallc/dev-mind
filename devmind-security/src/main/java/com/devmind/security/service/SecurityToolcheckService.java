package com.devmind.security.service;

import com.devmind.execution.model.StepResult;
import com.devmind.execution.model.StepSpec;
import com.devmind.execution.runner.AgentNodeStepRunner;
import com.devmind.security.engine.ScanEngineAdapter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点工具自检（CAP-66 FR-12，ADMIN）：对目标节点逐引擎下发 --version 级轻量步骤，
 * 回显版本首行或缺失原因（runner 侧 scan.<engine>.path 未配 / 二进制不在 PATH）。
 * 同步短任务（单引擎 60s 超时），不落库。
 */
@Service
public class SecurityToolcheckService {

    private static final long TIMEOUT_SEC = 60;

    private final AgentNodeStepRunner stepRunner;
    private final List<ScanEngineAdapter> adapters;

    public SecurityToolcheckService(AgentNodeStepRunner stepRunner, List<ScanEngineAdapter> adapters) {
        this.stepRunner = stepRunner;
        this.adapters = adapters;
    }

    public record ToolcheckItem(String engine, String displayName, boolean ok, String output) {
    }

    public List<ToolcheckItem> check(String nodeId) {
        List<ToolcheckItem> out = new ArrayList<>();
        int stepIndex = 0;
        for (ScanEngineAdapter adapter : adapters) {
            List<String> lines = new ArrayList<>();
            StepResult r = stepRunner.runStep(nodeId, null, "security-toolcheck-" + nodeId,
                    ++stepIndex,
                    new StepSpec("toolcheck-" + adapter.engineId(),
                            adapter.versionCommand() + " 2>&1 | head -3", null, "security-scan"),
                    Map.of("DEVMIND_SCAN_ENGINE", adapter.engineId()), null, null, TIMEOUT_SEC,
                    lines::add);
            String output = lines.isEmpty() ? (r.error() == null ? "" : r.error())
                    : String.join("\n", lines);
            out.add(new ToolcheckItem(adapter.engineId(), adapter.displayName(), r.ok(),
                    r.ok() ? output
                            : "工具不可用（exit=" + r.exitCode() + "）：" + output
                            + "；请检查 runner agent.properties 的 scan." + adapter.engineId()
                            + ".path 或将 " + adapter.binaryName() + " 放入 PATH"));
        }
        return out;
    }

    /** 供前端渲染的 map 形态（保持与引擎子状态同构） */
    public List<Map<String, Object>> checkAsMaps(String nodeId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolcheckItem item : check(nodeId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engine", item.engine());
            m.put("displayName", item.displayName());
            m.put("ok", item.ok());
            m.put("output", item.output());
            out.add(m);
        }
        return out;
    }
}
