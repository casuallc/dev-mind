package com.devmind.decisionlab.checkpoint;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 产物三份 JSON（指标 / 校准 / 自检报告）的读写。
 *
 * <p>口径与 {@code DatasetJson} 一致：<b>解析失败不上抛，返回空 map</b>——详情页上一条读不出来的报告
 * 不该变成 500，而"没有指标"与"指标读不出来"在界面上都要人去跑一次评测，处理方式相同。
 * 写侧反过来：序列化失败必须抛（写不进去还装作成功，等于把指标静默丢掉）。</p>
 */
@Component
public class CheckpointJson {

    private static final Logger log = LoggerFactory.getLogger(CheckpointJson.class);

    private static final TypeReference<Map<String, Object>> GENERIC = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public CheckpointJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(json, GENERIC);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            log.warn("决策实验室 JSON 解析失败（按空处理）: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    /** null 存 null（"没评过"与"评了个空"要分得开，同 {@code DatasetJson.write}） */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("决策实验室 JSON 序列化失败: " + e.getMessage(), e);
        }
    }
}
