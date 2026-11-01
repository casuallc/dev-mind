package com.devmind.test.config;

import com.devmind.common.security.CorsProperties;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import com.devmind.test.dto.TestPlanRunView;
import com.devmind.test.dto.TestRunView;
import com.devmind.test.model.TestPlanRunEntity;
import com.devmind.test.model.TestRunEntity;
import com.devmind.test.repo.TestRunRepository;
import com.devmind.test.service.TestPlanRunService;
import com.devmind.test.service.TestRunService;
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
    private final TestRunService runService;
    private final TestPlanRunService planRunService;
    private final ObjectMapper mapper;
    private final CorsProperties corsProperties;

    public TestWebSocketConfig(ExecutionLogHub hub, TestRunRepository runRepo, TestRunService runService,
                               TestPlanRunService planRunService,
                               ObjectMapper mapper, CorsProperties corsProperties) {
        this.hub = hub;
        this.runRepo = runRepo;
        this.runService = runService;
        this.planRunService = planRunService;
        this.mapper = mapper;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ExecutionWsHandler(hub, this::snapshot, mapper, "/test-runs/"),
                        "/ws/test-runs/**")
                .setAllowedOrigins(corsProperties.originsArray());
        registry.addHandler(new ExecutionWsHandler(hub, this::planSnapshot, mapper, "/test-plan-runs/"),
                        "/ws/test-plan-runs/**")
                .setAllowedOrigins(corsProperties.originsArray());
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
        TestRunView view;
        try {
            view = runService.get(id);
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("runId", id);
        extra.put("status", r.getStatus());
        extra.put("baseUrl", r.getBaseUrl() == null ? "" : r.getBaseUrl());
        extra.put("results", view.results());
        return new ExecutionSnapshot(null, r.getStatus(), isTerminal(r.getStatus()), extra);
    }

    /** 方案运行快照：汇总 + 方案项结果 + 子运行（topic 形如 plan-{id}）。 */
    private ExecutionSnapshot planSnapshot(String topic) {
        Long id = parseLong(topic == null ? null : topic.replaceFirst("^plan-", ""));
        if (id == null) {
            return null;
        }
        TestPlanRunView view;
        try {
            view = planRunService.get(id);
        } catch (Exception e) {
            return null;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("planRunId", id);
        extra.put("status", view.status());
        extra.put("conclusion", view.conclusion());
        extra.put("summary", view.summary());
        extra.put("items", view.items());
        extra.put("children", view.children());
        return new ExecutionSnapshot(null, view.status(), isPlanTerminal(view.status()), extra);
    }

    private boolean isPlanTerminal(String status) {
        return TestPlanRunEntity.SUCCESS.equals(status) || TestPlanRunEntity.FAILED.equals(status)
                || TestPlanRunEntity.BLOCKED.equals(status) || TestPlanRunEntity.CANCELLED.equals(status);
    }

    private boolean isTerminal(String status) {
        return TestRunEntity.SUCCESS.equals(status) || TestRunEntity.FAILED.equals(status)
                || TestRunEntity.BLOCKED.equals(status);
    }

    private Long parseLong(String topic) {
        try {
            return Long.parseLong(topic);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
