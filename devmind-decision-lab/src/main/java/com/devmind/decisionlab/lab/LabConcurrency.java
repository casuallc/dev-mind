package com.devmind.decisionlab.lab;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.eval.repo.DecisionEvaluationRepository;
import com.devmind.decisionlab.finetune.model.DecisionFinetuneEntity;
import com.devmind.decisionlab.finetune.repo.DecisionFinetuneRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CAP-56 评测与微调<b>共用</b>的并发闸门：一次运行独占节点的一个执行许可，评测与微调都算。
 *
 * <p><b>为什么必须合成一个计数</b>：限流的理由是节点侧的许可（一个执行器同时只能跑一个
 * 重活），而许可不区分"这次是评测还是训练"。分成两个闸门的话，"2 个训练 + 2 个评测"就能在
 * 上限为 2 的配置下同时跑起来——两个闸门各自都觉得自己没超。这里的两张表数加起来，
 * 才是"节点上同时有几件重活"。</p>
 *
 * <p>状态词表以 {@code DecisionFinetuneEntity} 的常量为准（它别名自评测实体），
 * 所以两张表的"在跑"永远是同一个意思。</p>
 */
@Component
public class LabConcurrency {

    private static final List<String> ACTIVE =
            List.of(DecisionFinetuneEntity.QUEUED, DecisionFinetuneEntity.RUNNING);

    private final DecisionEvaluationRepository evalRepo;
    private final DecisionFinetuneRepository finetuneRepo;

    public LabConcurrency(DecisionEvaluationRepository evalRepo, DecisionFinetuneRepository finetuneRepo) {
        this.evalRepo = evalRepo;
        this.finetuneRepo = finetuneRepo;
    }

    /** 当前占用执行许可的运行数（排队中 + 运行中） */
    public long active() {
        return evalRepo.countByStatusIn(ACTIVE) + finetuneRepo.countByStatusIn(ACTIVE);
    }

    /**
     * 触发前的闸门：满了直接 409 带可操作提示。
     *
     * @param what 本次动作的名字（"评测"/"微调"），只进日志与提示语
     */
    public void requireCapacity(int limit, String what) {
        int max = Math.max(1, limit);
        long active = active();
        if (active >= max) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "同时进行的评测/微调已达上限 " + max + "（配置项 devmind.decision-lab.max-concurrent-runs）："
                            + "一次运行会独占节点的一个执行许可，跑太多会把节点许可占满"
                            + "（本次发起的是「" + what + "」）");
        }
    }
}
