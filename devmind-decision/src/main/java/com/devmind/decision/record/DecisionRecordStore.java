package com.devmind.decision.record;

import com.devmind.common.decision.DecisionRecordSink;
import com.devmind.common.decision.DecisionResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-55 FR-05 {@link DecisionRecordSink} 的实现：把建议/裁决序列化成 JSON 快照，
 * 交给 {@link DecisionRecordWriter} 落 {@code decision_records}（每 (capability, subjectId) 一行）。
 *
 * <p><b>绝不抛</b>（SPI 契约）：序列化失败、唯一键竞态、库暂时写不进——一律记日志收场。
 * 数据飞轮少一条样本是损失，但把这条损失升级成"提案采纳失败"就不是损失而是事故了。</p>
 *
 * <p><b>竞态重试一次</b>：同一实体的"重新分诊"与"人工裁决"可能几乎同时落行，
 * 两边都查不到行就会双插入，后到的吃唯一键冲突。让出一次后重试即可走 update 分支；
 * 因为写入是 REQUIRES_NEW，第一次失败不会污染调用方，也不会污染第二次。</p>
 */
@Component
public class DecisionRecordStore implements DecisionRecordSink {

    private static final Logger log = LoggerFactory.getLogger(DecisionRecordStore.class);

    /** 唯一键竞态的重试等待：对面那条记录就在事务里，毫秒级就提交完了 */
    static final long RETRY_BACKOFF_MS = 50;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DecisionRecordWriter writer;

    public DecisionRecordStore(DecisionRecordWriter writer) {
        this.writer = writer;
    }

    @Override
    public void saveSuggestion(String capability, String refId, Map<String, Object> state,
                               Map<String, Map<String, Object>> questions, DecisionResult result) {
        if (result == null) {
            return;
        }
        String stateJson = json(state == null ? Map.of() : state);
        String questionsJson = json(questions == null ? Map.of() : questions);
        String answerJson = json(result.answers());
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("model", result.routingModel());
        routing.put("reason", result.routingReason());
        String routingJson = json(routing);
        if (stateJson == null || questionsJson == null || answerJson == null || routingJson == null) {
            // 键是自家实体，正常不该失败；真失败了就当这次没记（业务照常）
            log.warn("决策样本序列化失败（不落库）: capability={} ref={}", capability, refId);
            return;
        }
        writeWithRetry("建议", capability, refId, () -> writer.writeSuggestion(capability, refId,
                stateJson, questionsJson, answerJson, routingJson, result.degraded(),
                result.degradedReason(), result.latencyMs(), Instant.now()));
    }

    @Override
    public void saveVerdict(String capability, String refId, String humanAction, Map<String, Object> gold,
                            String by) {
        String goldJson = json(gold == null ? Map.of() : gold);
        if (goldJson == null) {
            log.warn("人工裁决序列化失败（不落库）: capability={} ref={}", capability, refId);
            return;
        }
        writeWithRetry("裁决", capability, refId, () -> writer.writeVerdict(capability, refId,
                humanAction, goldJson, by, Instant.now()));
    }

    private void writeWithRetry(String what, String capability, String refId, Runnable write) {
        try {
            write.run();
            return;
        } catch (Exception first) {
            log.debug("决策记录{}首次写入失败，重试一次: capability={} ref={} err={}",
                    what, capability, refId, first.toString());
            sleep(RETRY_BACKOFF_MS);
        }
        try {
            write.run();
        } catch (Exception second) {
            log.warn("决策记录{}写入失败（不影响业务）: capability={} ref={} err={}",
                    what, capability, refId, second.toString());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 序列化失败返回 null（不抛）：Caller 据 null 记一行日志跳过 */
    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
