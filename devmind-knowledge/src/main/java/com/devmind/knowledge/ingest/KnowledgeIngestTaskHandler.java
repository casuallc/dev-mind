package com.devmind.knowledge.ingest;

import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.NonRetryableTaskException;
import com.devmind.task.api.TaskContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-84 FR-05 摄入任务：payload {@code {"batchId":"..."}}，逐文件解析/去重/版本/索引。
 * 单文件失败已在服务内落状态并继续；本 handler 只在批次级异常时抛出以触发重试。
 */
@Component
public class KnowledgeIngestTaskHandler implements AsyncTaskHandler {

    private final LocalImportService importService;
    private final ObjectMapper mapper;

    public KnowledgeIngestTaskHandler(LocalImportService importService, ObjectMapper mapper) {
        this.importService = importService;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return LocalImportService.TASK_TYPE;
    }

    @Override
    public String description() {
        return "知识库本地上传摄入（解析/去重/版本/分块/索引）";
    }

    @Override
    public String payloadTemplate() {
        return "{\n  \"batchId\": \"<导入批次 id>\"\n}";
    }

    @Override
    public void execute(String payload, TaskContext ctx) throws Exception {
        JsonNode p = payload == null || payload.isBlank() ? mapper.createObjectNode() : mapper.readTree(payload);
        String batchId = p.path("batchId").asText("");
        if (batchId.isBlank()) {
            throw new NonRetryableTaskException("payload.batchId 不能为空");
        }
        importService.processBatch(batchId, ctx);
    }
}
