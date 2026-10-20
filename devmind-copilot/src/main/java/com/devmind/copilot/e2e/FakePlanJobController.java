package com.devmind.copilot.e2e;

import com.devmind.common.agent.plan.JobState;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E 假作业状态翻转端点（仅 e2e profile，CAP-76 场景 12）：脚本 POST 翻转状态，
 * 驱动计划 await 的「等待→继续」与「作业失败→中断」两条路径。
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/e2e/plan-jobs")
public class FakePlanJobController {

    private final FakePlanJobProbe probe;

    public FakePlanJobController(FakePlanJobProbe probe) {
        this.probe = probe;
    }

    public record Flip(String id, String state) {
    }

    /** 翻转作业状态：state ∈ RUNNING/SUCCEEDED/FAILED/UNKNOWN。 */
    @PostMapping
    public Map<String, Object> flip(@RequestBody Flip in) {
        probe.setState(in.id(), JobState.valueOf(in.state()));
        return Map.of("ok", true);
    }

    @GetMapping
    public Map<String, JobState> states() {
        return probe.states();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        probe.reset();
        return Map.of("ok", true);
    }
}
