package com.devmind.test.config;

import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import com.devmind.test.dto.CaseResultView;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestCaseResultRepository;
import com.devmind.test.repo.TestRunRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册 /ws/test-runs/** 测试实时流（收尾2 起复用执行底座 ExecutionWsHandler/ExecutionLogHub）。
 * topic = runId 字符串；快照帧带业务字段 runId/status/baseUrl/results，
 * 帧协议与原 TestRunWsHandler 完全一致（result / done），前端无改动。
 */
@Configuration
@EnableWebSocket
public class TestWebSocketConfig implements WebSocketConfigurer {

    private final ExecutionLogHub hub;
    private final TestRunRepository runRepo;
    private final TestCaseResultRepository resultRepo;
    private final ObjectMapper mapper;

    public TestWebSocketConfig(ExecutionLogHub hub, TestRunRepository runRepo,
                               TestCaseResultRepository resultRepo, ObjectMapper mapper) {
        this.hub = hub;
        this.runRepo = runRepo;
        this.resultRepo = resultRepo;
        this.mapper = mapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ExecutionWsHandler(hub, this::snapshot, mapper, "/test-runs/"),
                        "/ws/test-runs/**")
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }

    /** 测试运行快照：已完成用例结果 + baseUrl（日志不重复快照，前端只看实时流） */
    private ExecutionSnapshot snapshot(String topic) {
        Long id = parseLong(topic);
        if (id == null) {
            return null;
        }
        TestRunEntity r = runRepo.findById(id).orElse(null);
        if (r == null) {
            return null;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("runId", id);
        extra.put("status", r.getStatus());
        extra.put("baseUrl", r.getBaseUrl() == null ? "" : r.getBaseUrl());
        extra.put("results", resultRepo.findByRunIdOrderBySortAsc(id).stream()
                .map(e -> new CaseResultView(e.getId(), e.getCaseId(), e.getSuiteId(), e.getSort(), e.getName(),
                        e.getStatus(), e.getRequestSummary(), e.getResponseSummary(), e.getError(), e.getDuration()))
                .toList());
        return new ExecutionSnapshot(null, r.getStatus(), isTerminal(r.getStatus()), extra);
    }

    private boolean isTerminal(String status) {
        return TestRunEntity.SUCCESS.equals(status) || TestRunEntity.FAILED.equals(status);
    }

    private Long parseLong(String topic) {
        try {
            return Long.parseLong(topic);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
