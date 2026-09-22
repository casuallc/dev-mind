package com.devmind.knowledge.triage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * CAP-55 FR-04 分诊触发器：新提案自动分诊（异步）+ 手动分诊入口。
 *
 * <p><b>为什么手写单线程执行器而不用 {@code @Async}</b>：与 {@code KnowledgeIndexListener}
 * 同款先例——单线程把并发压成 1，边车是 CPU 前向，一次几十条并发只会互相拖慢；
 * 队列天然给"入库高峰"做了背压。<b>方法本身禁 {@code @Transactional}</b>（红线）：
 * 分诊是网络 IO，事务里等远端会把连接池拖垮，落库另有短事务。</p>
 *
 * <p>事件走 {@code AFTER_COMMIT}（fallbackExecution 兼容无事务上下文）：别在提交前就跑去读
 * 刚 save 的行——异步线程看不到未提交数据，是踩过一次的坑。</p>
 */
@Component
public class KnowledgeTriageListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTriageListener.class);

    private final KnowledgeTriageService triageService;

    private final ExecutorService triageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-triage");
        t.setDaemon(true);
        return t;
    });

    public KnowledgeTriageListener(KnowledgeTriageService triageService) {
        this.triageService = triageService;
    }

    /** 新提案入库 → 排队分诊 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProposalCreated(ProposalCreatedEvent event) {
        submit(event.proposalId());
    }

    /**
     * 投递一次分诊（手动触发的入口）：只排队不等结果——HTTP 线程不该被模型前向占住，
     * 前端拿 202 后轮询提案列表看徽标出现。
     */
    public void submit(long proposalId) {
        triageExecutor.submit(() -> {
            try {
                triageService.triage(proposalId);
            } catch (Exception e) {
                // triage() 自己已兜住各层异常，这里是最后一道：异步线程里抛出只会变成静默丢任务
                log.warn("分诊任务异常: proposal={} err={}", proposalId, e.toString());
            }
        });
    }
}
