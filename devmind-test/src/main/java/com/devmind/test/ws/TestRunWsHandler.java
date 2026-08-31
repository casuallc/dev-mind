package com.devmind.test.ws;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import com.devmind.test.dto.CaseResultView;
import com.devmind.test.model.TestCaseResultEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestRunRepository;

/**
 * CAP-10 测试实时流：WS /ws/test-runs/{id}/stream。
 * 连接后先推快照（运行状态 + 已完成结果），再实时收 result 增量，终态收 done。
 */
@Component
public class TestRunWsHandler extends TextWebSocketHandler {

    private final TestHub hub;
    private final TestRunRepository runRepo;
    private final TestCaseResultRepository resultRepo;
    private final ObjectMapper mapper;
    private final Map<WebSocketSession, Long> runBySession = new ConcurrentHashMap<>();

    public TestRunWsHandler(TestHub hub, TestRunRepository runRepo,
                            TestCaseResultRepository resultRepo, ObjectMapper mapper) {
        this.hub = hub;
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long id = parseId(session.getUri());
        if (id == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        runBySession.put(session, id);
        hub.subscribe(id, session);
        TestRunEntity r = runRepo.findById(id).orElse(null);
        if (r == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        List<CaseResultView> results = resultRepo.findByRunIdOrderBySortAsc(id).stream()
                .map(this::toResultView).toList();
        send(session, mapper.writeValueAsString(Map.of(
                "type", "snapshot",
                "runId", id,
                "status", r.getStatus(),
                "baseUrl", r.getBaseUrl() == null ? "" : r.getBaseUrl(),
                "results", results)));
        if (isTerminal(r.getStatus())) {
            send(session, mapper.writeValueAsString(Map.of("type", "done", "status", r.getStatus())));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String type = mapper.readTree(message.getPayload()).path("type").asText("");
        if ("ping".equals(type)) {
            send(session, mapper.writeValueAsString(Map.of("type", "pong")));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long id = runBySession.remove(session);
        if (id != null) {
            hub.unsubscribe(id, session);
        }
    }

    private boolean isTerminal(String status) {
        return TestRunEntity.SUCCESS.equals(status) || TestRunEntity.FAILED.equals(status);
    }

    private CaseResultView toResultView(TestCaseResultEntity e) {
        return new CaseResultView(e.getId(), e.getCaseId(), e.getSuiteId(), e.getSort(), e.getName(),
                e.getStatus(), e.getRequestSummary(), e.getResponseSummary(), e.getError(), e.getDuration());
    }

    private void send(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception e) {
            // 会话已关闭则忽略
        }
    }

    private Long parseId(URI uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.getPath();
        int idx = path.indexOf("/test-runs/");
        if (idx < 0) {
            return null;
        }
        String rest = path.substring(idx + "/test-runs/".length());
        int slash = rest.indexOf('/');
        String id = slash > 0 ? rest.substring(0, slash) : rest;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
