package com.devmind.knowledge.triage;

import com.devmind.common.decision.DecisionRecordSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * CAP-55 FR-05 把人工裁决写进决策记录（{@code decision_records}），与分诊时的模型建议配成一行。
 *
 * <p>同步处理、不开线程池：这是一次 DB upsert（毫秒级），不是分诊那种百毫秒网络往返——
 * 为了它引一条队列只会让"裁决已生效但记录还没落"的窗口凭空出现。</p>
 *
 * <p>没装配决策能力（{@code devmind-decision} 不在场）就静默跳过：记录是旁路资产，
 * 缺了它提案采纳照常走完。</p>
 */
@Component
public class ProposalVerdictListener {

    private static final Logger log = LoggerFactory.getLogger(ProposalVerdictListener.class);

    private final ObjectProvider<DecisionRecordSink> sinkProvider;

    public ProposalVerdictListener(ObjectProvider<DecisionRecordSink> sinkProvider) {
        this.sinkProvider = sinkProvider;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVerdict(ProposalVerdictEvent event) {
        DecisionRecordSink sink = sinkProvider.getIfAvailable();
        if (sink == null) {
            return;
        }
        try {
            // 契约是"失败不抛"，这里再兜一层：裁决已经生效了，记录写不进也不该回吐给用户
            sink.saveVerdict(KnowledgeTriageService.CAPABILITY, String.valueOf(event.proposalId()),
                    event.humanAction(), event.gold(), event.by());
        } catch (Exception e) {
            log.warn("人工裁决记录失败（不影响采纳/拒绝本身）: proposal={} err={}",
                    event.proposalId(), e.toString());
        }
    }
}
