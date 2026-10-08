package com.devmind.usage.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.project.model.ProjectEntity;
import com.devmind.project.model.RequirementEntity;
import com.devmind.project.repo.ProjectRepository;
import com.devmind.project.repo.RequirementRepository;
import com.devmind.usage.dto.UsageBreakdownRow;
import com.devmind.usage.dto.UsageDailyPoint;
import com.devmind.usage.dto.UsageSummary;
import com.devmind.usage.dto.UsageTopRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CAP-67 用量统计聚合服务单测：两源合并、四维分组、每日分桶、Top 明细与 owner 强制。
 * 仓储走 {@link FakeUsageRepos} 内存实现（全仓无 @SpringBootTest 约定）。
 */
class UsageStatsServiceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final FakeUsageRepos repos = new FakeUsageRepos();
    private final Map<String, RequirementEntity> requirements = new HashMap<>();
    private final Map<String, ProjectEntity> projects = new HashMap<>();
    private final Map<String, UserEntity> users = new HashMap<>();

    private String actor = "alice";
    private boolean admin;

    private UsageStatsService service;

    @BeforeEach
    void setUp() {
        RequirementRepository reqRepo = proxy(RequirementRepository.class, (p, m, args) -> {
            if (m.getName().equals("findAllById")) {
                List<RequirementEntity> out = new ArrayList<>();
                for (Object id : (Iterable<?>) args[0]) {
                    if (requirements.containsKey(id.toString())) {
                        out.add(requirements.get(id.toString()));
                    }
                }
                return out;
            }
            throw new UnsupportedOperationException(m.getName());
        });
        ProjectRepository projRepo = proxy(ProjectRepository.class, (p, m, args) -> {
            if (m.getName().equals("findAllById")) {
                List<ProjectEntity> out = new ArrayList<>();
                for (Object id : (Iterable<?>) args[0]) {
                    if (projects.containsKey(id.toString())) {
                        out.add(projects.get(id.toString()));
                    }
                }
                return out;
            }
            throw new UnsupportedOperationException(m.getName());
        });
        UserRepository userRepo = proxy(UserRepository.class, (p, m, args) -> {
            if (m.getName().equals("findByUsername")) {
                return Optional.ofNullable(users.get((String) args[0]));
            }
            throw new UnsupportedOperationException(m.getName());
        });
        IdentityService identity = new IdentityService(null, null, null) {
            @Override
            public String currentActor() {
                return actor;
            }

            @Override
            public Optional<UserEntity> currentUser() {
                UserEntity u = new UserEntity();
                u.setUsername(actor);
                u.setRole(admin ? UserEntity.ROLE_ADMIN : "DEVELOPER");
                return Optional.of(u);
            }
        };
        service = new UsageStatsService(repos.sessionRepo(), repos.chatRepo(), reqRepo, projRepo, userRepo, identity);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private static Instant day(int offsetDays) {
        return LocalDate.now().minusDays(offsetDays).atTime(12, 0).atZone(ZONE).toInstant();
    }

    private void seed() {
        requirements.put("r1", requirement("r1", "p1", "需求一"));
        projects.put("p1", project("p1", "项目一"));
        users.put("alice", user("alice", "爱丽丝"));
        users.put("bob", user("bob", null));
        // 会话：r1/alice/sonnet 今天；无归属/bob/空模型 昨天
        repos.sessions.add(FakeUsageRepos.session("s1", "r1", "p1", "sonnet", "alice", day(0),
                0.10, 1000, 200, 2));
        repos.sessions.add(FakeUsageRepos.session("s2", null, null, null, "bob", day(1),
                0.05, 500, 100, 1));
        // 问答：alice/sonnet 今天
        repos.chats.add(FakeUsageRepos.chat("c1", "sonnet", "alice", day(0), 0.02, 300, 60, 1));
    }

    private static RequirementEntity requirement(String id, String projectId, String title) {
        RequirementEntity r = new RequirementEntity();
        r.setId(id);
        r.setProjectId(projectId);
        r.setTitle(title);
        return r;
    }

    private static ProjectEntity project(String id, String name) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private static UserEntity user(String username, String displayName) {
        UserEntity u = new UserEntity();
        u.setUsername(username);
        u.setDisplayName(displayName);
        return u;
    }

    @Test
    void summaryMergesBothSourcesForAdmin() {
        seed();
        admin = true;
        UsageSummary sum = service.summary(null, null, null);
        assertEquals(0.17, sum.costUsd(), 1e-9);
        assertEquals(1800, sum.inputTokens());
        assertEquals(360, sum.outputTokens());
        assertEquals(4, sum.turnCount());
        assertEquals(2, sum.sessionCount());
        assertEquals(1, sum.chatCount());
    }

    @Test
    void summaryForcesSelfForNonAdmin() {
        seed();
        // 非 admin 传 userId=他人也必须被忽略
        UsageSummary sum = service.summary(null, null, "bob");
        assertEquals(0.12, sum.costUsd(), 1e-9);
        assertEquals(1, sum.sessionCount());
        assertEquals(1, sum.chatCount());
    }

    @Test
    void summaryFiltersByTimeRange() {
        seed();
        admin = true;
        Instant todayStart = LocalDate.now().atStartOfDay(ZONE).toInstant();
        UsageSummary sum = service.summary(todayStart, null, null);
        assertEquals(0.12, sum.costUsd(), 1e-9); // 只有今天的 s1 + c1
        assertEquals(1, sum.sessionCount());
        assertEquals(1, sum.chatCount());
    }

    @Test
    void breakdownRequirementEnrichesLabelsAndAddsChatBucket() {
        seed();
        admin = true;
        List<UsageBreakdownRow> rows = service.breakdown("requirement", null, null, null);
        assertEquals(3, rows.size());
        UsageBreakdownRow first = rows.get(0); // 成本降序：r1 0.10 居首
        assertEquals("r1", first.key());
        assertEquals("需求一", first.label());
        assertEquals("p1", first.projectId());
        assertEquals(1, first.sessionCount());
        assertEquals(0, first.chatCount());
        assertTrue(rows.stream().anyMatch(r -> r.key() == null
                && UsageStatsService.BUCKET_SESSIONS.equals(r.label()) && r.sessionCount() == 1));
        assertTrue(rows.stream().anyMatch(r -> r.key() == null
                && UsageStatsService.BUCKET_CHATS.equals(r.label()) && r.chatCount() == 1));
    }

    @Test
    void breakdownProjectEnrichesNames() {
        seed();
        admin = true;
        List<UsageBreakdownRow> rows = service.breakdown("project", null, null, null);
        UsageBreakdownRow first = rows.get(0);
        assertEquals("p1", first.key());
        assertEquals("项目一", first.label());
    }

    @Test
    void breakdownModelMergesBothSourcesAndDefaultsBlank() {
        seed();
        admin = true;
        List<UsageBreakdownRow> rows = service.breakdown("model", null, null, null);
        assertEquals(2, rows.size());
        UsageBreakdownRow sonnet = rows.stream().filter(r -> "sonnet".equals(r.key())).findFirst().orElseThrow();
        assertEquals(1, sonnet.sessionCount());
        assertEquals(1, sonnet.chatCount());
        assertEquals(0.12, sonnet.costUsd(), 1e-9);
        UsageBreakdownRow blank = rows.stream().filter(r -> r.key() == null).findFirst().orElseThrow();
        assertEquals(UsageStatsService.LABEL_DEFAULT_MODEL, blank.label());
    }

    @Test
    void breakdownUserRequiresAdmin() {
        seed();
        assertThrows(DevMindException.class, () -> service.breakdown("user", null, null, null));
    }

    @Test
    void breakdownUserMergesBothSourcesWithDisplayNames() {
        seed();
        admin = true;
        List<UsageBreakdownRow> rows = service.breakdown("user", null, null, null);
        assertEquals(2, rows.size());
        UsageBreakdownRow alice = rows.stream().filter(r -> "alice".equals(r.key())).findFirst().orElseThrow();
        assertEquals("爱丽丝", alice.label());
        assertEquals(1, alice.sessionCount());
        assertEquals(1, alice.chatCount());
        UsageBreakdownRow bob = rows.stream().filter(r -> "bob".equals(r.key())).findFirst().orElseThrow();
        assertEquals("bob", bob.label()); // 无显示名回落用户名
    }

    @Test
    void breakdownRejectsUnknownDim() {
        admin = true;
        assertThrows(DevMindException.class, () -> service.breakdown("node", null, null, null));
    }

    @Test
    void dailyBucketsByCreatedDayAndFillsZeros() {
        seed();
        admin = true;
        List<UsageDailyPoint> points = service.daily(3, null);
        assertEquals(3, points.size());
        UsageDailyPoint today = points.get(2);
        assertEquals(LocalDate.now().toString(), today.date());
        assertEquals(0.12, today.costUsd(), 1e-9); // s1 + c1
        assertEquals(1560, today.tokens());
        assertEquals(3, today.turns());
        UsageDailyPoint yesterday = points.get(1);
        assertEquals(0.05, yesterday.costUsd(), 1e-9); // s2
        UsageDailyPoint twoDaysAgo = points.get(0);
        assertEquals(0, twoDaysAgo.turns()); // 零填充
    }

    @Test
    void topMergesBothSourcesSortedByCostWithRequirementTitle() {
        seed();
        admin = true;
        List<UsageTopRow> rows = service.top(10, null, null, null);
        assertEquals(3, rows.size());
        assertEquals("SESSION", rows.get(0).source());
        assertEquals("任务 s1", rows.get(0).title());
        assertEquals("需求一", rows.get(0).requirementTitle());
        assertEquals("SESSION", rows.get(1).source());
        assertEquals(0.05, rows.get(1).costUsd(), 1e-9); // s2
        assertEquals("CHAT", rows.get(2).source());
        assertEquals("问答 c1", rows.get(2).title());
    }

    @Test
    void topHonorsLimit() {
        seed();
        admin = true;
        List<UsageTopRow> rows = service.top(2, null, null, null);
        assertEquals(2, rows.size());
        assertEquals(0.10, rows.get(0).costUsd(), 1e-9);
    }
}
