package com.devmind.decisionlab.dataset;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-56 评测样本三份 JSON 的读写。
 *
 * <p><b>解析失败不上抛，返回空 map</b>（同 {@code TriageViews} 的口径）：读路径上一条脏 JSON
 * 不该让整页 500。空 map 会被上层当成"没标"——样本因此进不了可评分的集合，这是<b>可见的</b>降级，
 * 比抛异常炸掉整个列表好；反过来若把脏 JSON 当"标好了"，评测会拿一条空 gold 去算指标，
 * 那才是真的错。</p>
 */
@Component
public class DatasetJson {

    private static final Logger log = LoggerFactory.getLogger(DatasetJson.class);

    private static final TypeReference<Map<String, Object>> GENERIC = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Map<String, Object>>> QUESTIONS = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public DatasetJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> parse(String json) {
        return read(json, GENERIC, LinkedHashMap::new);
    }

    /** 题面：{题 id: {type,instructions,criteria}} */
    public Map<String, Map<String, Object>> parseQuestions(String json) {
        return read(json, QUESTIONS, LinkedHashMap::new);
    }

    /** 序列化：null 存 null（空值就是没标，别存成 "{}"，否则"没标"与"标了个空"分不开） */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("评测样本序列化失败: " + e.getMessage(), e);
        }
    }

    private <T> T read(String json, TypeReference<T> type, Supplier<T> empty) {
        if (json == null || json.isBlank()) {
            return empty.get();
        }
        try {
            T parsed = mapper.readValue(json, type);
            return parsed == null ? empty.get() : parsed;
        } catch (Exception e) {
            log.warn("评测样本 JSON 解析失败（按未标处理）: {}", e.toString());
            return empty.get();
        }
    }
}
