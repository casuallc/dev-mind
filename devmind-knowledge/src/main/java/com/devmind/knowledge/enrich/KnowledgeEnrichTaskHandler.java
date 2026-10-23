package com.devmind.knowledge.enrich;

import com.devmind.task.api.AsyncTaskHandler;
import com.devmind.task.api.TaskContext;
import org.springframework.stereotype.Component;

/**
 * CAP-85 FR-28 增强任务：payload {@code {"kbId":?,"entryId":?,"limit":?}}。
 * 单条失败已在服务内写 enrich_error 并继续，本 handler 不重试整批。
 */
@Component
public class KnowledgeEnrichTaskHandler implements AsyncTaskHandler {

    private final KnowledgeEnrichService enrichService;

    public KnowledgeEnrichTaskHandler(KnowledgeEnrichService enrichService) {
        this.enrichService = enrichService;
    }

    @Override
    public String type() {
        return KnowledgeEnrichService.TASK_TYPE;
    }

    @Override
    public String description() {
        return "知识自动分类/摘要/标签（CHAT 端点，人工修正优先）";
    }

    @Override
    public String payloadTemplate() {
        return "{\n  \"kbId\": null,\n  \"entryId\": null,\n  \"limit\": 20\n}";
    }

    @Override
    public void execute(String payload, TaskContext ctx) {
        enrichService.execute(payload, ctx);
    }
}
