package com.devmind.knowledge.index;

import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.TaskContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-84 FR-09 重建索引任务：payload {@code {"kbId": 123}}（省略 = 全部知识库）。
 * 逐条重跑分块与 Lucene 写入，结束后 flush；条目较多时耗时长，可取消。
 */
@Component
public class KnowledgeRebuildTaskHandler implements AsyncTaskHandler {

    public static final String TASK_TYPE = "knowledge.rebuild";

    private final KnowledgeIndexService indexService;
    private final ObjectMapper mapper;

    public KnowledgeRebuildTaskHandler(KnowledgeIndexService indexService, ObjectMapper mapper) {
        this.indexService = indexService;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return TASK_TYPE;
    }

    @Override
    public String description() {
        return "知识库重建检索索引（结构化分块 + BM25/向量）";
    }

    @Override
    public String payloadTemplate() {
        return "{\n  \"kbId\": null\n}";
    }

    @Override
    public void execute(String payload, TaskContext ctx) {
        Long kbId = null;
        if (payload != null && !payload.isBlank()) {
            JsonNode p = mapper.readTree(payload);
            if (p.hasNonNull("kbId")) {
                kbId = p.get("kbId").asLong();
            }
        }
        indexService.rebuildAll(kbId, ctx);
    }
}
