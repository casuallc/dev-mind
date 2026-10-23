package com.devmind.knowledge.index;

import com.devmind.knowledge.EntryContentChangedEvent;
import com.devmind.task.api.TaskOptions;
import com.devmind.task.service.AsyncTaskService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * CAP-44 FR-04 索引触发器（异步，单线程执行器仿 RequirementFlowService.flowExecutor 先例）。
 * 条目内容变更事件 AFTER_COMMIT 后才投递（防异步线程读不到未提交行——红线场景）；
 * 无事务上下文（fallbackExecution）立即投递。启动后清扫 pending/disabled 存量。
 */
@Component
public class KnowledgeIndexListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexListener.class);

    private final KnowledgeIndexService indexService;
    private final AsyncTaskService taskService;

    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-index");
        t.setDaemon(true);
        return t;
    });

    public KnowledgeIndexListener(KnowledgeIndexService indexService, AsyncTaskService taskService) {
        this.indexService = indexService;
        this.taskService = taskService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEntryContentChanged(EntryContentChangedEvent event) {
        indexExecutor.submit(() -> {
            try {
                indexService.indexEntry(event.entryId());
            } catch (Exception e) {
                log.warn("知识条目索引任务异常: entry={} err={}", event.entryId(), e.toString());
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        indexExecutor.submit(() -> {
            try {
                indexService.sweepPending();
                KnowledgeIndexService.IndexHealth health = indexService.health();
                log.info("知识索引健康度: DB 条目 {} / 索引块 {} / 向量维度 {}",
                        health.dbEntries(), health.indexedDocs(), health.vectorDimensions());
                if (health.dbEntries() > 0 && health.indexedDocs() == 0) {
                    // 索引目录被清空/损坏或 schema 升级后自动重建（FR-09），无需人工点重建
                    String taskId = taskService.submit(KnowledgeRebuildTaskHandler.TASK_TYPE, "{}",
                            TaskOptions.builder()
                                    .name("启动自动重建知识索引")
                                    .retryMaxAttempts(1)
                                    .timeoutMs(12 * 60 * 60_000L)
                                    .build());
                    log.warn("知识库索引为空但存在 {} 个条目，已提交自动重建任务: {}",
                            health.dbEntries(), taskId);
                }
            } catch (Exception e) {
                log.warn("知识索引启动清扫异常: {}", e.toString());
            }
        });
    }
}
