package com.devmind.knowledge.index;

import com.devmind.knowledge.EntryContentChangedEvent;
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

    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-index");
        t.setDaemon(true);
        return t;
    });

    public KnowledgeIndexListener(KnowledgeIndexService indexService) {
        this.indexService = indexService;
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
            } catch (Exception e) {
                log.warn("知识索引启动清扫异常: {}", e.toString());
            }
        });
    }
}
