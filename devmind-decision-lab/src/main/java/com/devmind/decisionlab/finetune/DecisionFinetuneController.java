package com.devmind.decisionlab.finetune;

import com.devmind.common.dto.PageView;
import com.devmind.decisionlab.finetune.dto.FinetuneDetail;
import com.devmind.decisionlab.finetune.dto.FinetuneTriggerRequest;
import com.devmind.decisionlab.finetune.dto.FinetuneView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-56 FR-05 微调任务 API（{@code /api/decision/finetunes}）。
 *
 * <p>与评测控制器同形：分页口径一致（{@code page} 从 0 起、{@code size} 1-200）、
 * <b>没有 PUT</b>（一次训练是一份已发生的事实：在哪个节点上、用哪个切分、跑出什么指标，
 * 它不是可以编辑的对象；要换超参就再跑一次，两次都留着正好是一条实验记录）。</p>
 */
@RestController
@RequestMapping("/api/decision/finetunes")
public class DecisionFinetuneController {

    private final FinetuneService service;

    public DecisionFinetuneController(FinetuneService service) {
        this.service = service;
    }

    @GetMapping
    public PageView<FinetuneView> list(@RequestParam(required = false) Long datasetId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.list(datasetId, page, size);
    }

    /** 发起微调（异步；立即返回 QUEUED 行，日志走 /ws/decision-lab/finetunes/{id}） */
    @PostMapping
    public FinetuneView trigger(@RequestBody FinetuneTriggerRequest req) {
        return service.trigger(req);
    }

    /** 详情：视图 + 报告 + 验证集逐题明细 + 校准 + 训练过程 + 切分明细 */
    @GetMapping("/{id}")
    public FinetuneDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    /** 人读日志（已剔 marker 行；含收尾阶段"登记产物/触发回评"的那几行） */
    @GetMapping("/{id}/logs")
    public String logs(@PathVariable Long id) {
        return service.logs(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
