package com.devmind.classify.instance.dto;

import com.devmind.classify.instance.model.ClassifyInstanceEntity;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 实体 → 视图（JSON 列在这里一次性解析，解析失败按空 Map 兜底不炸列表） */
public final class ClassifyInstanceViews {

    private static final TypeReference<Map<String, String>> ENV_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> HEALTH_TYPE = new TypeReference<>() {
    };

    private ClassifyInstanceViews() {
    }

    public static ClassifyInstanceView of(ClassifyInstanceEntity e, ObjectMapper mapper) {
        return new ClassifyInstanceView(e.getId(), e.getName(), e.getAgentNodeId(), e.getPort(),
                e.getBaseUrl(), e.getAppPackageId(), e.getPythonBin(),
                parse(mapper, e.getEnvJson(), ENV_TYPE), e.getCommandOverride(), e.getStatus(),
                e.getLastStartAt(), e.getLastHealthAt(), parse(mapper, e.getLastHealthJson(), HEALTH_TYPE),
                e.getLastError(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private static <T> T parse(ObjectMapper mapper, String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }
}
