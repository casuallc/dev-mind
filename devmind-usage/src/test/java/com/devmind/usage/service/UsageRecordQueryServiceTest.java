package com.devmind.usage.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.chat.model.ChatSessionEntity;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.session.model.SessionEntity;
import com.devmind.usage.dto.UsageHourlyPoint;
import com.devmind.usage.dto.UsageRecordRow;
import com.devmind.usage.model.UsageRecordEntity;
import com.devmind.usage.repo.UsageChatStatsRepository;
import com.devmind.usage.repo.UsageRecordHourlyRow;
import com.devmind.usage.repo.UsageRecordRepository;
import com.devmind.usage.repo.UsageSessionStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.InvocationHandler;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账本只读查询（{@link UsageRecordQueryService}）的回归网：owner 强制（CAP-62 口径）、
 * source 白名单校验、分页/标题截断映射、tokensPerSec 分支、hourly 24 桶零填充。
 * JPQL 过滤本身不在单测覆盖（H2 集成由 E2E 守），这里钉参数透传与 Java 侧逻辑。
 */
class UsageRecordQueryServiceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final List<UsageRecordEntity> pageRows = new ArrayList<>();
    private final List<UsageRecordHourlyRow> hourlyRows = new ArrayList<>();
    private final Map<String, SessionEntity> sessions = new HashMap<>();
    private final Map<String, ChatSessionEntity> chats = new HashMap<>();
    /** pageMatching 实际收到的参数（owner/source/model/pageable） */
    private final List<Object[]> captured = new ArrayList<>();
    private long countResult = 0;
    private String actor = "alice";
    private boolean admin = false;

    private UsageRecordQueryService service;

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    @BeforeEach
    void setUp() {
        UsageRecordRepository recordRepo = proxy(UsageRecordRepository.class, (p, m, args) -> switch (m.getName()) {
            case "pageMatching" -> {
                captured.add(new Object[]{args[2], args[3], args[4], args[5]});
                yield pageRows;
            }
            case "countMatching" -> countResult;
            case "hourlyRows" -> hourlyRows;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        UsageSessionStatsRepository sessionRepo = proxy(UsageSessionStatsRepository.class, (p, m, args) -> {
            if ("findAllById".equals(m.getName())) {
                List<SessionEntity> out = new ArrayList<>();
                for (Object id : (Iterable<String>) args[0]) {
                    if (sessions.containsKey(id)) {
                        out.add(sessions.get(id));
                    }
                }
                return out;
            }
            throw new UnsupportedOperationException(m.getName());
        });
        UsageChatStatsRepository chatRepo = proxy(UsageChatStatsRepository.class, (p, m, args) -> {
            if ("findAllById".equals(m.getName())) {
                List<ChatSessionEntity> out = new ArrayList<>();
                for (Object id : (Iterable<String>) args[0]) {
                    if (chats.containsKey(id)) {
                        out.add(chats.get(id));
                    }
                }
                return out;
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
        service = new UsageRecordQueryService(recordRepo, sessionRepo, chatRepo, identity);
    }

    private static UsageRecordEntity record(String source, String sessionId, String owner,
                                            Long outputTokens, Long durationMs) {
        UsageRecordEntity e = new UsageRecordEntity();
        e.setSource(source);
        e.setSessionId(sessionId);
        e.setOwner(owner);
        e.setOutputTokens(outputTokens);
        e.setDurationMs(durationMs);
        e.setCreatedAt(Instant.now());
        return e;
    }

    private static Instant todayAt(int hour) {
        return LocalDate.now(ZONE).atTime(hour, 30).atZone(ZONE).toInstant();
    }

    @Test
    void 非管理员_忽略userId强制本人() {
        service.records(0, 20, null, null, null, null, "bob");
        assertEquals("alice", captured.get(0)[0], "非 admin 必须强制本人 owner");
        assertNull(captured.get(0)[1]);
        assertNull(captured.get(0)[2]);
    }

    @Test
    void 管理员_缺省看全部_传值按值() {
        admin = true;
        service.records(0, 20, null, null, null, null, null);
        assertNull(captured.get(0)[0], "admin 缺省 owner=null 看全部");
        service.records(0, 20, null, null, null, null, "bob");
        assertEquals("bob", captured.get(1)[0]);
    }

    @Test
    void source白名单_未知值400() {
        assertThrows(DevMindException.class,
                () -> service.records(0, 20, "OTHER", null, null, null, null));
        assertEquals(0, captured.size(), "校验失败不得触达仓储");
    }

    @Test
    void 空串过滤参数归一为null() {
        service.records(0, 20, "  ", "  ", null, null, null);
        assertNull(captured.get(0)[1]);
        assertNull(captured.get(0)[2]);
    }

    @Test
    void 分页映射_标题截断_归属补全_tokensPerSec() {
        countResult = 3;
        UsageRecordEntity r1 = record("SESSION", "s1", "alice", 340L, 2000L);
        r1.setId(11L);
        r1.setInputTokens(1200L);
        UsageRecordEntity r2 = record("CHAT", "c1", "alice", null, null);
        r2.setId(12L);
        UsageRecordEntity r3 = record("SESSION", "ghost", "alice", 100L, 0L);
        r3.setId(13L);
        pageRows.addAll(List.of(r1, r2, r3));
        SessionEntity s = new SessionEntity();
        s.setId("s1");
        s.setTaskSpec("  第一行\n第二行" + "x".repeat(200));
        s.setRequirementId("r1");
        s.setProjectId("p1");
        sessions.put("s1", s);
        ChatSessionEntity c = new ChatSessionEntity();
        c.setId("c1");
        c.setTitle("问答标题");
        chats.put("c1", c);

        PageView<UsageRecordRow> page = service.records(1, 2, null, null, null, null, null);

        assertEquals(3, page.total());
        assertEquals(1, page.page());
        assertEquals(2, page.size());
        assertEquals(3, page.items().size());

        UsageRecordRow row1 = page.items().get(0);
        assertEquals(11L, row1.id());
        assertEquals(120, row1.title().length() - 1, "截断 120 字符加省略号");
        assertTrue(row1.title().endsWith("…"));
        assertTrue(!row1.title().contains("\n"), "标题必须单行化");
        assertEquals("r1", row1.requirementId());
        assertEquals("p1", row1.projectId());
        assertEquals(170.0, row1.tokensPerSec(), "340*1000/2000");

        UsageRecordRow row2 = page.items().get(1);
        assertEquals("问答标题", row2.title());
        assertNull(row2.tokensPerSec(), "输出缺失 → null");

        UsageRecordRow row3 = page.items().get(2);
        assertNull(row3.title(), "实体缺席（已删会话）标题为 null 不炸");
        assertNull(row3.tokensPerSec(), "durationMs=0 → null 防除零");
    }

    @Test
    void hourly_24桶零填充_按服务器时区归属() {
        // 今天 3 点两回合、23 点一回合；createdAt 缺失行跳过
        hourlyRows.add(new UsageRecordHourlyRow(todayAt(3), 1000L, 200L));
        hourlyRows.add(new UsageRecordHourlyRow(todayAt(3), null, 60L));
        hourlyRows.add(new UsageRecordHourlyRow(todayAt(23), 500L, 500L));
        hourlyRows.add(new UsageRecordHourlyRow(null, 9L, 9L));

        List<UsageHourlyPoint> out = service.hourly(null, null);

        assertEquals(24, out.size());
        assertEquals("00", out.get(0).hour());
        assertEquals("23", out.get(23).hour());
        assertEquals(1260L, out.get(3).tokens());
        assertEquals(2L, out.get(3).turns());
        assertEquals(1000L, out.get(23).tokens());
        assertEquals(0L, out.get(4).tokens());
        assertEquals(0L, out.get(4).turns());
    }
}
