package com.devmind.decision.record;

import com.devmind.decision.record.model.DecisionRecordEntity;
import com.devmind.decision.record.repo.DecisionRecordRepository;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-55 FR-05 记录行的落库口（短事务，只管读写，不碰 JSON 语义——那在
 * {@link DecisionRecordStore} 里）。
 *
 * <p><b>为什么是 REQUIRES_NEW</b>：记录是旁路资产。挂在业务事务里的话，业务回滚会把
 * "人确实做过这个决定"这件事一起抹掉（而它已经发生了）；反过来业务也不该被一条记录写入拖累。
 * 调用方（分诊异步线程、裁决 AFTER_COMMIT 监听）本就没有事务，这里显式声明是为了
 * 不被未来的调用方无意间拉进事务——{@link DecisionRecordStore} 的失败重试也依赖
 * "失败只回滚这一条记录"这一点。</p>
 */
@Component
public class DecisionRecordWriter {

    private final DecisionRecordRepository repo;

    public DecisionRecordWriter(DecisionRecordRepository repo) {
        this.repo = repo;
    }

    /** 写入（覆盖）模型建议部分；已有行的裁决部分原样保留（人已经做过的决定不能被重诊抹掉）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeSuggestion(String capability, String subjectId, String stateJson, String questionsJson,
                                String answerJson, String routingJson, boolean degraded, String degradedReason,
                                long latencyMs, Instant suggestedAt) {
        DecisionRecordEntity row = findOrCreate(capability, subjectId);
        row.setStateJson(stateJson);
        row.setQuestionsJson(questionsJson);
        row.setModelAnswer(answerJson);
        row.setRoutingJson(routingJson);
        row.setDegraded(degraded);
        row.setDegradedReason(degradedReason);
        row.setLatencyMs((int) latencyMs);
        row.setSuggestedAt(suggestedAt);
        row.setUpdatedAt(Instant.now());
        repo.save(row);
    }

    /** 写入（覆盖）人工裁决部分；建议部分原样保留（同一次分诊的样本要配对，不能各写各的）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeVerdict(String capability, String subjectId, String humanAction, String goldJson,
                             String decidedBy, Instant decidedAt) {
        DecisionRecordEntity row = findOrCreate(capability, subjectId);
        row.setHumanAction(humanAction);
        row.setGoldJson(goldJson);
        row.setDecidedBy(decidedBy);
        row.setDecidedAt(decidedAt);
        row.setUpdatedAt(Instant.now());
        repo.save(row);
    }

    private DecisionRecordEntity findOrCreate(String capability, String subjectId) {
        DecisionRecordEntity row = repo.findByCapabilityAndSubjectId(capability, subjectId).orElse(null);
        if (row != null) {
            return row;
        }
        DecisionRecordEntity created = new DecisionRecordEntity();
        created.setCapability(capability);
        created.setSubjectId(subjectId);
        created.setDegraded(false);
        created.setCreatedAt(Instant.now());
        return created;
    }
}
