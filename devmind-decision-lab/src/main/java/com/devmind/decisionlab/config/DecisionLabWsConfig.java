package com.devmind.decisionlab.config;

import com.devmind.common.security.CorsProperties;
import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-56 决策实验室实时流：{@code /ws/decision-lab/evaluations/**}（topic = 评测 id）。
 * 完全照 {@code BuildWebSocketConfig}：统一执行底座的通用
 * {@link ExecutionWsHandler} 承担收发，本配置只提供"这个 topic 的历史日志与终态怎么查"。
 *
 * <p>帧类型（前端共用同一套）：{@code log} 日志行、{@code item} 逐题事件（
 * {@code {"type":"item","item":{…}}}）、{@code done} 收尾。</p>
 *
 * <p>微调任务的流在 Phase 7 用同一形态注册 {@code /ws/decision-lab/finetunes/**}。</p>
 */
@Configuration
@EnableWebSocket
public class DecisionLabWsConfig implements WebSocketConfigurer {

    private final ExecutionLogHub hub;
    private final DecisionEvaluationRepository repo;
    private final ObjectMapper mapper;
    private final CorsProperties corsProperties;

    public DecisionLabWsConfig(ExecutionLogHub hub, DecisionEvaluationRepository repo,
                               ObjectMapper mapper, CorsProperties corsProperties) {
        this.hub = hub;
        this.repo = repo;
        this.mapper = mapper;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        new ExecutionWsHandler(hub, this::snapshot, mapper, "/decision-lab/evaluations/"),
                        "/ws/decision-lab/evaluations/**")
                .setAllowedOrigins(corsProperties.originsArray());
    }

    /** 按评测 id 提供历史日志快照与终态；id 非法或记录不存在返回 null（关闭连接） */
    private ExecutionSnapshot snapshot(String topic) {
        Long id;
        try {
            id = Long.parseLong(topic);
        } catch (NumberFormatException e) {
            return null;
        }
        DecisionEvaluationEntity e = repo.findById(id).orElse(null);
        return e == null ? null : new ExecutionSnapshot(e.getLogsText(), e.getStatus(), e.isTerminal());
    }
}
