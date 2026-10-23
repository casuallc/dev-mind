package com.devmind.knowledge.sync;

import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.NonRetryableTaskException;
import com.devmind.task.api.TaskContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-85 FR-27：知识同步任务。payload {@code {"sourceId": 123}}；
 * 单篇失败已在同步服务内计数继续，只有整源失败（配置/列举/水位异常）才抛出。
 */
@Component
public class KnowledgeSyncTaskHandler implements AsyncTaskHandler {

    private final KnowledgeSyncService syncService;
    private final ObjectMapper mapper;

    public KnowledgeSyncTaskHandler(KnowledgeSyncService syncService, ObjectMapper mapper) {
        this.syncService = syncService;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return KnowledgeSyncService.TASK_TYPE;
    }

    @Override
    public String description() {
        return "知识库外部同步（Git 文档目录 / 飞书空间）";
    }

    @Override
    public String payloadTemplate() {
        return "{\n  \"sourceId\": 0\n}";
    }

    @Override
    public void execute(String payload, TaskContext ctx) {
        JsonNode p = payload == null || payload.isBlank()
                ? mapper.createObjectNode() : mapper.readTree(payload);
        long sourceId = p.path("sourceId").asLong(0);
        if (sourceId <= 0) {
            throw new NonRetryableTaskException("payload.sourceId 不能为空");
        }
        syncService.execute(sourceId, ctx);
    }
}
