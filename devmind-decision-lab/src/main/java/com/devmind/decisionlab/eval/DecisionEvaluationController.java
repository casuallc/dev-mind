package com.devmind.decisionlab.eval;

import com.devmind.common.dto.PageView;
import com.devmind.decisionlab.eval.dto.EvalDetail;
import com.devmind.decisionlab.eval.dto.EvalTriggerRequest;
import com.devmind.decisionlab.eval.dto.EvalView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-56 FR-03 评测运行 API（{@code /api/decision/evaluations}）。
 *
 * <p>分页口径与全平台一致：{@code page} 从 0 起、{@code size} 限制 [1,200]。</p>
 *
 * <p><b>没有 PUT</b>：一次评测是一份已发生的证据（在某个节点上、用某份冻结集、跑出来的数字），
 * 它不是可以编辑的对象。要重测就再跑一次——两次都留着，正好是一条时间线。</p>
 */
@RestController
@RequestMapping("/api/decision/evaluations")
public class DecisionEvaluationController {

    private final EvalService service;

    public DecisionEvaluationController(EvalService service) {
        this.service = service;
    }

    @GetMapping
    public PageView<EvalView> list(@RequestParam(required = false) Long datasetId,
                                   @RequestParam(required = false) Long checkpointId,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return service.list(datasetId, checkpointId, page, size);
    }

    /** 发起评测（异步；立即返回 QUEUED 行，日志走 /ws/decision-lab/evaluations/{id}） */
    @PostMapping
    public EvalView trigger(@RequestBody EvalTriggerRequest req) {
        return service.trigger(req);
    }

    /** 详情：视图 + 完整报告 + 对照组分解 + 逐题明细 + 校准/对照 */
    @GetMapping("/{id}")
    public EvalDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    /** 人读日志（已剔 marker 行；机器载荷在详情里以结构化形式给出） */
    @GetMapping("/{id}/logs")
    public String logs(@PathVariable Long id) {
        return service.logs(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
