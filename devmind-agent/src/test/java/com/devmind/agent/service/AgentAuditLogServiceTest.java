package com.devmind.agent.service;

import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.repo.AgentAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentAuditLogService：Entry→实体映射与截断、落库失败旁路降级、search 参数钳制、
 * purge 按保留期委托。组合 SQL 的过滤语义由 tests/cap71_e2e.py 对真实库覆盖（全仓无 @SpringBootTest）。
 */
class AgentAuditLogServiceTest {

    private AgentAuditLogRepository repo;
    private AgentAuditLogService service;

    @BeforeEach
    void setUp() {
        repo = mock(AgentAuditLogRepository.class);
        service = new AgentAuditLogService(repo);
    }

    @Test
    void recordMapsAllFields() {
        service.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_PROXY, "OPEN")
                .nodeId(7L).nodeName("n7").actor("admin").remoteAddr("127.0.0.1:5000")
                .target("gitlab.corp.com:443").result(AgentAuditLogEntity.RESULT_OK)
                .bytes(3, 5).durationMs(42).detail("socks5"));

        ArgumentCaptor<AgentAuditLogEntity> cap = ArgumentCaptor.forClass(AgentAuditLogEntity.class);
        verify(repo).save(cap.capture());
        AgentAuditLogEntity e = cap.getValue();
        assertEquals(AgentAuditLogEntity.CAT_PROXY, e.getCategory());
        assertEquals("OPEN", e.getEvent());
        assertEquals(7L, e.getNodeId());
        assertEquals("n7", e.getNodeName());
        assertEquals("admin", e.getActor());
        assertEquals("127.0.0.1:5000", e.getRemoteAddr());
        assertEquals("gitlab.corp.com:443", e.getTarget());
        assertEquals(AgentAuditLogEntity.RESULT_OK, e.getResult());
        assertEquals(3L, e.getBytesUp());
        assertEquals(5L, e.getBytesDown());
        assertEquals(42L, e.getDurationMs());
        assertEquals("socks5", e.getDetail());
        assertTrue(e.getCreatedAt() != null);
    }

    @Test
    void recordTruncatesOverlongColumns() {
        service.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_COMMAND, "EXEC")
                .target("t".repeat(300)).detail("d".repeat(600)));
        ArgumentCaptor<AgentAuditLogEntity> cap = ArgumentCaptor.forClass(AgentAuditLogEntity.class);
        verify(repo).save(cap.capture());
        assertEquals(255, cap.getValue().getTarget().length());
        assertEquals(500, cap.getValue().getDetail().length());
    }

    @Test
    void recordFailureDegradesToWarnWithoutThrowing() {
        doThrow(new RuntimeException("db down")).when(repo).save(any());
        // 旁路语义：落库失败绝不抛给主流程（WS 派发/隧道 relay 线程）
        service.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONNECTION, "CONNECT"));
    }

    @Test
    void searchClampsPageSizeAndBlanksToNull() {
        ArgumentCaptor<PageRequest> pageCap = ArgumentCaptor.forClass(PageRequest.class);
        ArgumentCaptor<String> kwCap = ArgumentCaptor.forClass(String.class);
        when(repo.search(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.search("", "  ", null, "", "  ", -1, 9999);
        verify(repo).search(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                kwCap.capture(), pageCap.capture());
        assertNull(kwCap.getValue()); // 全空白关键词按不过滤处理
        assertEquals(0, pageCap.getValue().getPageNumber()); // 负页钳回 0
        assertEquals(AgentAuditLogService.MAX_PAGE_SIZE, pageCap.getValue().getPageSize()); // 上限 200
    }

    @Test
    void purgeDelegatesWithRetentionCutoff() {
        when(repo.deleteByCreatedAtBefore(any())).thenReturn(5L);
        Instant before = Instant.now();
        long deleted = service.purge(180);
        assertEquals(5L, deleted);
        ArgumentCaptor<Instant> cap = ArgumentCaptor.forClass(Instant.class);
        verify(repo).deleteByCreatedAtBefore(cap.capture());
        // cutoff ≈ now - 180 天（容许秒级偏差）
        long days = java.time.Duration.between(cap.getValue(), before).toDays();
        assertEquals(180L, days);
    }
}
