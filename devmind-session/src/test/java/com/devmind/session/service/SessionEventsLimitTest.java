package com.devmind.session.service;

import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.event.DomainEventPublisher;
import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEntity;
import com.devmind.session.model.SessionEventEntity;
import com.devmind.session.repo.SessionEventRepository;
import com.devmind.session.repo.SessionRepository;
import com.devmind.session.runtime.SessionEventSaver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CAP-50：会话事件补拉的条数上限与「最近 N 条」语义。
 *
 * <p>倒序取页再反转是这里唯一容易写错的地方——顺序错了前端会把整段正文倒着拼，
 * 且只在长会话（超过 limit）才显形，故用假仓库钉死：取的是<b>最近</b>而非最早 N 条、
 * 返回<b>升序</b>、limit&lt;=0 落默认值、超上限按硬上限截断。</p>
 */
class SessionEventsLimitTest {

    private final List<SessionEventEntity> rows = new ArrayList<>();
    /** 假仓库实际收到的页大小——用于验证默认值与硬上限截断。 */
    private final AtomicInteger pageSizeSeen = new AtomicInteger();

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private void seed(int count) {
        for (int seq = 1; seq <= count; seq++) {
            SessionEventEntity e = new SessionEventEntity();
            e.setSessionId("s1");
            e.setSeq((long) seq);
            e.setType(seq % 2 == 0 ? "assistant" : "text_delta");
            e.setContent("c" + seq);
            e.setSource("stdout");
            e.setCreatedAt(Instant.now());
            rows.add(e);
        }
    }

    private SessionManagerService service() {
        SessionEntity ent = new SessionEntity();
        ent.setId("s1");
        ent.setStatus("DONE");
        ent.setCreatedAt(Instant.now());
        ent.setUpdatedAt(Instant.now());
        SessionRepository repo = proxy(SessionRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findById" -> "s1".equals(args[0]) ? Optional.of(ent) : Optional.empty();
            default -> throw new UnsupportedOperationException(m.getName());
        });
        // 忠实复刻派生查询：seq > afterSeq，倒序，页大小即 limit
        SessionEventRepository eventRepo = proxy(SessionEventRepository.class, (p, m, args) -> switch (m.getName()) {
            case "findBySessionIdAndSeqGreaterThanOrderBySeqDesc" -> {
                long after = (Long) args[1];
                Pageable page = (Pageable) args[2];
                pageSizeSeen.set(page.getPageSize());
                yield rows.stream()
                        .filter(e -> e.getSeq() > after)
                        .sorted(Comparator.comparingLong(SessionEventEntity::getSeq).reversed())
                        .limit(page.getPageSize())
                        .toList();
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
        SessionProperties props = new SessionProperties();
        SessionEventSaver saver = new SessionEventSaver(eventRepo, props, JsonMapper.builder().build());
        ObjectProvider<AgentNodeConnector> connectorProvider =
                proxy(ObjectProvider.class, (p, m, args) -> null);
        return new SessionManagerService(null, null, null, null, null, null, null,
                e -> { }, new DomainEventPublisher(e -> { }),
                repo, eventRepo, null, null, saver, props, JsonMapper.builder().build(),
                connectorProvider, null, null, null, null, null);
    }

    @Test
    void 默认返回全部且按seq升序() {
        seed(10);
        List<Long> seqs = service().events("s1", -1, 0).stream().map(e -> e.seq()).toList();

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), seqs, "倒序取的回正顺序不能错");
        assertEquals(SessionManagerService.DEFAULT_EVENT_LIMIT, pageSizeSeen.get(), "limit<=0 应落默认上限");
    }

    @Test
    void 超上限时取最近而非最早() {
        seed(10);
        List<Long> seqs = service().events("s1", -1, 3).stream().map(e -> e.seq()).toList();

        assertEquals(List.of(8L, 9L, 10L), seqs, "被截断时保留下来的必须是最近几条，且仍升序");
    }

    @Test
    void limit超硬上限按硬上限截断() {
        seed(3);
        service().events("s1", -1, 999_999);

        assertEquals(SessionManagerService.MAX_EVENT_LIMIT, pageSizeSeen.get(),
                "调用方传超大 limit 不能变成整表拉取");
    }

    @Test
    void afterSeq只挪起点不影响产出顺序() {
        seed(10);
        List<Long> seqs = service().events("s1", 8, 0).stream().map(e -> e.seq()).toList();

        assertEquals(List.of(9L, 10L), seqs);
    }
}
