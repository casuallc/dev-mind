package com.devmind.test.dto;

import java.time.Instant;
import java.util.List;

/** test_runs.execution_snapshot_json 的结构（只用于方案子运行；普通运行保持空 = 实时读取）。 */
public record RunExecutionSnapshot(Instant capturedAt, List<PlanSnapshot.TestCaseSnapshot> cases) {
}
