package com.devmind.test.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.EnvironmentService;
import com.devmind.project.ProjectService;
import com.devmind.project.dto.EnvironmentView;
import com.devmind.test.dto.TestStatsView;
import com.devmind.test.model.TestCaseEntity;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestPlanRunEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseRepository;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestPlanRunRepository;
import com.devmind.test.repo.TestRunRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CAP-104 FR-21 统计：按需聚合运行结果。
 * 通过率 = PASS / (PASS + FAIL + ERROR)；BLOCKED/SKIP 不计入分母但单独展示。
 * flaky：窗口内同用例既有 PASS 又有 FAIL/ERROR（样本数达阈值），或重试后通过。
 */
@Service
public class TestStatsService {

    private static final String DAY = "DAY";
    private static final String WEEK = "WEEK";

    private final ProjectService projectService;
    private final EnvironmentService environmentService;
    private final TestRunRepository runRepo;
    private final TestPlanRunRepository planRunRepo;
    private final TestCaseResultRepository resultRepo;
    private final TestCaseRepository caseRepo;

    public TestStatsService(ProjectService projectService, EnvironmentService environmentService,
                            TestRunRepository runRepo, TestPlanRunRepository planRunRepo,
                            TestCaseResultRepository resultRepo, TestCaseRepository caseRepo) {
        this.projectService = projectService;
        this.environmentService = environmentService;
        this.runRepo = runRepo;
        this.planRunRepo = planRunRepo;
        this.resultRepo = resultRepo;
        this.caseRepo = caseRepo;
    }

    public TestStatsView stats(String projectId, Integer days, String granularity, Integer minSamples,
                               Long planId, Long environmentId, Integer topLimit) {
        projectService.requireProject(projectId);
        int windowDays = days == null ? 30 : Math.max(1, Math.min(365, days));
        String gran = granularity == null || granularity.isBlank()
                ? DAY : granularity.strip().toUpperCase(Locale.ROOT);
        if (!DAY.equals(gran) && !WEEK.equals(gran)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "granularity 只能是 DAY / WEEK");
        }
        int minSampleCount = minSamples == null ? 5 : Math.max(1, Math.min(100, minSamples));
        int limit = topLimit == null ? 10 : Math.max(1, Math.min(100, topLimit));
        Instant to = Instant.now();
        Instant from = to.minusSeconds(windowDays * 86_400L);

        List<TestRunEntity> runs = loadRuns(projectId, planId, from).stream()
                .filter(r -> environmentId == null || environmentId.equals(r.getEnvironmentId()))
                .toList();
        Map<Long, TestRunEntity> runById = new LinkedHashMap<>();
        runs.forEach(r -> runById.put(r.getId(), r));
        List<TestCaseResultEntity> results = runById.isEmpty() ? List.of()
                : resultRepo.findByRunIdInOrderByCreatedAtAsc(runById.keySet());
        Map<Long, String> caseKeys = caseKeys(results);

        Statistics total = new Statistics();
        for (TestCaseResultEntity r : results) {
            total.accept(r.getStatus(), r.getDuration());
        }
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("runs", runs.size());
        overview.put("cases", total.total());
        overview.put("distinctCases", distinctCaseCount(results));
        overview.put("passed", total.passed);
        overview.put("failed", total.failed);
        overview.put("errors", total.errors);
        overview.put("blocked", total.blocked);
        overview.put("skipped", total.skipped);
        overview.put("passRate", total.passRate());
        overview.put("avgDurationMs", avg(total.executedDurations));

        return new TestStatsView(from, to, gran, overview,
                trend(results, runById, gran),
                topFailures(results, runById, caseKeys, limit),
                flaky(results, runById, caseKeys, minSampleCount),
                duration(results, caseKeys),
                environments(runs, results, projectId, limit));
    }

    private List<TestRunEntity> loadRuns(String projectId, Long planId, Instant from) {
        if (planId == null) {
            return runRepo.findByProjectIdAndCreatedAtAfterOrderByCreatedAtAsc(projectId, from);
        }
        List<Long> planRunIds = planRunRepo
                .findByProjectIdAndPlanIdAndCreatedAtAfterOrderByCreatedAtAsc(projectId, planId, from)
                .stream().map(TestPlanRunEntity::getId).toList();
        if (planRunIds.isEmpty()) {
            return List.of();
        }
        return runRepo.findByPlanRunIdInOrderByIdAsc(planRunIds).stream()
                .filter(r -> r.getCreatedAt() == null || !r.getCreatedAt().isBefore(from))
                .toList();
    }

    private List<TestStatsView.TrendPoint> trend(List<TestCaseResultEntity> results,
                                                 Map<Long, TestRunEntity> runById, String granularity) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (TestCaseResultEntity r : results) {
            TestRunEntity run = runById.get(r.getRunId());
            Instant at = run == null ? r.getCreatedAt() : firstNonNull(run.getCreatedAt(), run.getStartedAt());
            if (at == null) {
                continue;
            }
            String key = bucket(at, granularity);
            Bucket b = buckets.computeIfAbsent(key, k -> new Bucket());
            b.stats.accept(r.getStatus(), r.getDuration());
            if (run != null) {
                b.runs.add(run.getId());
            }
        }
        return buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new TestStatsView.TrendPoint(e.getKey(), e.getValue().runs.size(),
                        e.getValue().stats.total(), e.getValue().stats.passed, e.getValue().stats.failed,
                        e.getValue().stats.errors, e.getValue().stats.blocked, e.getValue().stats.skipped,
                        e.getValue().stats.passRate()))
                .toList();
    }

    private List<TestStatsView.FailureStat> topFailures(List<TestCaseResultEntity> results,
                                                        Map<Long, TestRunEntity> runById,
                                                        Map<Long, String> caseKeys, int limit) {
        Map<String, Failure> groups = new HashMap<>();
        for (TestCaseResultEntity r : results) {
            String status = norm(r.getStatus());
            if (!"fail".equals(status) && !"error".equals(status)) {
                continue;
            }
            String key = caseKey(r, caseKeys);
            Failure f = groups.computeIfAbsent(key, k -> new Failure());
            f.caseId = r.getCaseId();
            f.name = r.getName();
            if ("fail".equals(status)) {
                f.failures++;
            } else {
                f.errors++;
            }
            Instant at = r.getCreatedAt();
            if (at == null) {
                TestRunEntity run = runById.get(r.getRunId());
                at = run == null ? null : firstNonNull(run.getFinishedAt(), run.getCreatedAt());
            }
            if (f.lastAt == null || (at != null && at.isAfter(f.lastAt))) {
                f.lastAt = at;
                f.lastStatus = status;
                f.lastRunId = r.getRunId();
                f.lastError = r.getError();
                f.failureCategory = r.getFailureCategory();
            }
        }
        return groups.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().failures + b.getValue().errors,
                        a.getValue().failures + a.getValue().errors))
                .limit(limit)
                .map(e -> new TestStatsView.FailureStat(e.getValue().caseId,
                        caseKeys.get(e.getValue().caseId), e.getValue().name,
                        e.getValue().failures, e.getValue().errors, e.getValue().lastStatus,
                        e.getValue().lastRunId, e.getValue().lastAt, cap(e.getValue().lastError, 500),
                        e.getValue().failureCategory))
                .toList();
    }

    private List<TestStatsView.FlakyStat> flaky(List<TestCaseResultEntity> results,
                                               Map<Long, TestRunEntity> runById,
                                               Map<Long, String> caseKeys, int minSamples) {
        Map<String, Flaky> groups = new LinkedHashMap<>();
        Map<String, List<TestCaseResultEntity>> retryGroups = new LinkedHashMap<>();
        for (TestCaseResultEntity r : results) {
            String key = caseKey(r, caseKeys);
            Flaky f = groups.computeIfAbsent(key, k -> new Flaky());
            f.caseId = r.getCaseId();
            f.name = r.getName();
            String status = norm(r.getStatus());
            f.samples++;
            if ("pass".equals(status)) {
                f.passes++;
            } else if ("fail".equals(status) || "error".equals(status)) {
                f.failures++;
            } else if ("blocked".equals(status)) {
                f.blocked++;
            }
            Instant at = r.getCreatedAt();
            if (f.lastAt == null || (at != null && at.isAfter(f.lastAt))) {
                f.lastAt = at;
                f.lastStatus = status;
            }
            TestRunEntity run = runById.get(r.getRunId());
            if (run != null && run.getPlanItemId() != null && r.getCaseId() != null) {
                retryGroups.computeIfAbsent(run.getPlanItemId() + "|" + r.getCaseId(),
                        k -> new ArrayList<>()).add(r);
            }
        }
        Set<Long> retryPassed = new HashSet<>();
        for (List<TestCaseResultEntity> attempts : retryGroups.values()) {
            if (attempts.size() < 2) {
                continue;
            }
            attempts.sort(Comparator.comparing(a -> {
                TestRunEntity run = runById.get(a.getRunId());
                return run == null || run.getAttempt() == null ? 0 : run.getAttempt();
            }));
            TestCaseResultEntity last = attempts.get(attempts.size() - 1);
            boolean earlierFailure = attempts.subList(0, attempts.size() - 1).stream()
                    .map(a -> norm(a.getStatus()))
                    .anyMatch(s -> "fail".equals(s) || "error".equals(s));
            if ("pass".equals(norm(last.getStatus())) && earlierFailure && last.getCaseId() != null) {
                retryPassed.add(last.getCaseId());
            }
        }
        List<TestStatsView.FlakyStat> out = new ArrayList<>();
        for (Map.Entry<String, Flaky> e : groups.entrySet()) {
            Flaky f = e.getValue();
            boolean retryPass = f.caseId != null && retryPassed.contains(f.caseId);
            boolean mixed = f.samples >= minSamples && f.passes > 0 && f.failures > 0;
            if (!mixed && !retryPass) {
                continue;
            }
            int denom = f.passes + f.failures;
            out.add(new TestStatsView.FlakyStat(f.caseId, caseKeys.get(f.caseId), f.name, f.samples,
                    f.passes, f.failures, f.blocked,
                    denom == 0 ? null : Math.round(f.failures * 1000.0 / denom) / 10.0,
                    retryPass, f.lastStatus, f.lastAt));
        }
        out.sort((a, b) -> {
            int byRate = Double.compare(b.failRate() == null ? 0 : b.failRate(),
                    a.failRate() == null ? 0 : a.failRate());
            return byRate != 0 ? byRate : Integer.compare(b.samples(), a.samples());
        });
        return out.stream().limit(50).toList();
    }

    private Map<String, Object> duration(List<TestCaseResultEntity> results, Map<Long, String> caseKeys) {
        List<Long> all = new ArrayList<>();
        Map<String, List<Long>> byCase = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (TestCaseResultEntity r : results) {
            if (!executed(r.getStatus()) || r.getDuration() == null) {
                continue;
            }
            all.add(r.getDuration());
            String key = caseKey(r, caseKeys);
            byCase.computeIfAbsent(key, k -> new ArrayList<>()).add(r.getDuration());
            if (r.getName() != null) {
                names.putIfAbsent(key, r.getName());
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", all.size());
        m.put("p50Ms", percentile(all, 50));
        m.put("p95Ms", percentile(all, 95));
        m.put("avgMs", avg(all));
        List<Map<String, Object>> slow = new ArrayList<>();
        byCase.entrySet().stream()
                .sorted((a, b) -> Long.compare(percentile(b.getValue(), 95), percentile(a.getValue(), 95)))
                .limit(10)
                .forEach(e -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("caseId", parseCaseId(e.getKey()));
                    s.put("caseKey", caseKeys.get(parseCaseId(e.getKey())));
                    s.put("name", names.get(e.getKey()));
                    s.put("samples", e.getValue().size());
                    s.put("p95Ms", percentile(e.getValue(), 95));
                    slow.add(s);
                });
        m.put("slowCases", slow);
        return m;
    }

    private List<TestStatsView.EnvironmentStat> environments(List<TestRunEntity> runs,
                                                             List<TestCaseResultEntity> results,
                                                             String projectId, int limit) {
        Map<Long, String> names = new HashMap<>();
        try {
            for (EnvironmentView env : environmentService.list(projectId)) {
                names.put(env.id(), env.name());
            }
        } catch (Exception ignored) {
            // 环境已删除时只显示 id
        }
        Map<Long, TestRunEntity> runById = new HashMap<>();
        runs.forEach(r -> runById.put(r.getId(), r));
        Map<Long, Statistics> groups = new LinkedHashMap<>();
        Map<Long, Integer> runCounts = new LinkedHashMap<>();
        for (TestRunEntity r : runs) {
            Long key = r.getEnvironmentId() == null ? -1L : r.getEnvironmentId();
            runCounts.merge(key, 1, Integer::sum);
            groups.computeIfAbsent(key, k -> new Statistics());
        }
        for (TestCaseResultEntity r : results) {
            TestRunEntity run = runById.get(r.getRunId());
            Long key = run == null || run.getEnvironmentId() == null ? -1L : run.getEnvironmentId();
            groups.computeIfAbsent(key, k -> new Statistics()).accept(r.getStatus(), r.getDuration());
        }
        return groups.entrySet().stream()
                .sorted((a, b) -> Integer.compare(runCounts.getOrDefault(b.getKey(), 0),
                        runCounts.getOrDefault(a.getKey(), 0)))
                .limit(limit)
                .map(e -> new TestStatsView.EnvironmentStat(
                        e.getKey() < 0 ? null : e.getKey(),
                        e.getKey() < 0 ? "未指定环境" : names.getOrDefault(e.getKey(), "环境 #" + e.getKey()),
                        runCounts.getOrDefault(e.getKey(), 0), e.getValue().total(),
                        e.getValue().passed, e.getValue().failed, e.getValue().errors,
                        e.getValue().blocked, e.getValue().skipped, e.getValue().passRate(),
                        e.getValue().executedDurations.isEmpty() ? null
                                : percentile(e.getValue().executedDurations, 95)))
                .toList();
    }

    // ---------------- 辅助 ----------------

    private Map<Long, String> caseKeys(List<TestCaseResultEntity> results) {
        Set<Long> ids = new LinkedHashSet<>();
        for (TestCaseResultEntity r : results) {
            if (r.getCaseId() != null) {
                ids.add(r.getCaseId());
            }
        }
        Map<Long, String> keys = new HashMap<>();
        if (ids.isEmpty()) {
            return keys;
        }
        for (TestCaseEntity c : caseRepo.findAllById(ids)) {
            keys.put(c.getId(), c.getCaseKey());
        }
        return keys;
    }

    private static int distinctCaseCount(List<TestCaseResultEntity> results) {
        Set<Object> set = new HashSet<>();
        for (TestCaseResultEntity r : results) {
            set.add(r.getCaseId() == null ? "name:" + r.getName() : r.getCaseId());
        }
        return set.size();
    }

    private static String caseKey(TestCaseResultEntity r, Map<Long, String> caseKeys) {
        if (r.getCaseId() != null) {
            return "id:" + r.getCaseId();
        }
        return "name:" + (r.getName() == null ? "" : r.getName());
    }

    private static Long parseCaseId(String key) {
        if (key != null && key.startsWith("id:")) {
            try {
                return Long.parseLong(key.substring(3));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String bucket(Instant at, String granularity) {
        LocalDate date = LocalDate.ofInstant(at, ZoneId.systemDefault());
        if (DAY.equals(granularity)) {
            return date.toString();
        }
        WeekFields wf = WeekFields.ISO;
        int week = date.get(wf.weekOfWeekBasedYear());
        int year = date.get(wf.weekBasedYear());
        return String.format("%d-W%02d", year, week);
    }

    private static boolean executed(String status) {
        String s = norm(status);
        return "pass".equals(s) || "fail".equals(s) || "error".equals(s);
    }

    private static String norm(String status) {
        if (status == null) {
            return "";
        }
        String s = status.strip().toLowerCase(Locale.ROOT);
        return "skipped".equals(s) ? "skip" : s;
    }

    private static long percentile(List<Long> values, int p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double avg(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (Long v : values) {
            sum += v == null ? 0 : v;
        }
        return Math.round(sum * 10.0 / values.size()) / 10.0;
    }

    private static Instant firstNonNull(Instant a, Instant b) {
        return a != null ? a : b;
    }

    private static String cap(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static final class Bucket {
        final Statistics stats = new Statistics();
        final Set<Long> runs = new LinkedHashSet<>();
    }

    private static final class Failure {
        Long caseId;
        String name;
        int failures;
        int errors;
        String lastStatus;
        Long lastRunId;
        Instant lastAt;
        String lastError;
        String failureCategory;
    }

    private static final class Flaky {
        Long caseId;
        String name;
        int samples;
        int passes;
        int failures;
        int blocked;
        String lastStatus;
        Instant lastAt;
    }

    private static final class Statistics {
        int passed;
        int failed;
        int errors;
        int blocked;
        int skipped;
        final List<Long> executedDurations = new ArrayList<>();

        void accept(String status, Long durationMs) {
            switch (norm(status)) {
                case "pass" -> passed++;
                case "fail" -> failed++;
                case "error" -> errors++;
                case "blocked" -> blocked++;
                default -> skipped++;
            }
            if (executed(status) && durationMs != null) {
                executedDurations.add(durationMs);
            }
        }

        int total() {
            return passed + failed + errors + blocked + skipped;
        }

        Double passRate() {
            int d = passed + failed + errors;
            return d == 0 ? null : Math.round(passed * 1000.0 / d) / 10.0;
        }
    }
}
