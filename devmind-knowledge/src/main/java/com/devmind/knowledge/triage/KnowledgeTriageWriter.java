package com.devmind.knowledge.triage;

import com.devmind.knowledge.repo.KnowledgeProposalRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分诊结果落库（短事务）：写 {@code triage_json} / {@code triage_at} / {@code triage_degraded}。
 *
 * <p>独立成 bean 的理由与 {@code KnowledgeIndexWriter} 相同：决策调用是网络 IO + 模型计算
 * （几百毫秒到 3 秒超时），必须发生在事务<b>之外</b>——不能一边占着数据库连接一边等边车。
 * 事务边界收在这里：进方法时结果已齐，方法内只有纯写。</p>
 */
@Component
public class KnowledgeTriageWriter {

    private final KnowledgeProposalRepository proposalRepo;

    public KnowledgeTriageWriter(KnowledgeProposalRepository proposalRepo) {
        this.proposalRepo = proposalRepo;
    }

    /**
     * 覆盖写（同一提案重新分诊即盖掉旧的：UI 要的是"最近一次建议"，堆历史没有消费方）。
     * 提案在排队期间被删掉了就什么都不做——不是错误，也不用留日志。
     */
    @Transactional
    public void write(long proposalId, String triageJson, boolean degraded) {
        proposalRepo.findById(proposalId).ifPresent(p -> {
            p.setTriageJson(triageJson);
            p.setTriageAt(Instant.now());
            p.setTriageDegraded(degraded);
            proposalRepo.save(p);
        });
    }
}
