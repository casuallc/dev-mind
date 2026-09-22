package com.devmind.decisionlab.config;

import com.devmind.common.security.CorsProperties;
import com.devmind.decisionlab.eval.model.DecisionEvaluationEntity;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
import com.devmind.decisionlab.finetune.model.DecisionFinetuneEntity;
import com.devmind.decisionlab.finetune.repo.DecisionFinetuneRepository;
import com.devmind.execution.ws.ExecutionLogHub;
import com.devmind.execution.ws.ExecutionSnapshotProvider.ExecutionSnapshot;
import com.devmind.execution.ws.ExecutionWsHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-56 决策实验室实时流：{@code /ws/decision-lab/evaluations/**} 与
 * {@code /ws/decision-lab/finetunes/**}（topic = 对应任务的 id）。
 * 完全照 {@code BuildWebSocketConfig}：统一执行底座的通用
 * {@link ExecutionWsHandler} 承担收发，本配置只提供"这个 topic 的历史日志与终态怎么查"。
 *
 * <p>帧类型（前端共用同一套）：{@code log} 日志行、{@code item} 逐题事件（
 * {@code {"type":"item","item":{…}}}）、{@code done} 收尾。</p>
 *
 * <p>两个 topic 各自独立（id 空间是两张表），所以两个 handler 的 topic 前缀必须分开：
 * 共用一个前缀的话，评测 7 与微调 7 会订阅到同一条流——而"打开微调 7 的日志看到评测 7 的输出"
 * 是一种很难查的错。</p>
 */
@Configuration
@EnableWebSocket
public class DecisionLabWsConfig implements WebSocketConfigurer {

    private final ExecutionLogHub hub;
    private final DecisionEvaluationRepository evalRepo;
    private final DecisionFinetuneRepository finetuneRepo;
    private final ObjectMapper mapper;
    private final CorsProperties corsProperties;

    public DecisionLabWsConfig(ExecutionLogHub hub, DecisionEvaluationRepository evalRepo,
                               DecisionFinetuneRepository finetuneRepo, ObjectMapper mapper,
                               CorsProperties corsProperties) {
        this.hub = hub;
        this.evalRepo = evalRepo;
        this.finetuneRepo = finetuneRepo;
        this.mapper = mapper;
        this.corsProperties = corsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        new ExecutionWsHandler(hub, this::evalSnapshot, mapper, "/decision-lab/evaluations/"),
                        "/ws/decision-lab/evaluations/**")
                .setAllowedOrigins(corsProperties.originsArray());
        registry.addHandler(
                        new ExecutionWsHandler(hub, this::finetuneSnapshot, mapper, "/decision-lab/finetunes/"),
                        "/ws/decision-lab/finetunes/**")
                .setAllowedOrigins(corsProperties.originsArray());
    }

    /** 按评测 id 提供历史日志快照与终态；id 非法或记录不存在返回 null（关闭连接） */
    private ExecutionSnapshot evalSnapshot(String topic) {
        Long id = parse(topic);
        if (id == null) {
            return null;
        }
        DecisionEvaluationEntity e = evalRepo.findById(id).orElse(null);
        return e == null ? null : new ExecutionSnapshot(e.getLogsText(), e.getStatus(), e.isTerminal());
    }

    /** 按微调 id 提供历史日志快照与终态（微调日志里还包含收尾阶段登记产物/触发回评那几行） */
    private ExecutionSnapshot finetuneSnapshot(String topic) {
        Long id = parse(topic);
        if (id == null) {
            return null;
        }
        DecisionFinetuneEntity f = finetuneRepo.findById(id).orElse(null);
        return f == null ? null : new ExecutionSnapshot(f.getLogsText(), f.getStatus(), f.isTerminal());
    }

    private static Long parse(String topic) {
        try {
            return Long.parseLong(topic);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
