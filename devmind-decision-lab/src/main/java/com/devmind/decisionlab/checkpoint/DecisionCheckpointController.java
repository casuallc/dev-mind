package com.devmind.decisionlab.checkpoint;

import com.devmind.common.dto.PageView;
import com.devmind.decisionlab.checkpoint.dto.CheckpointDetail;
import com.devmind.decisionlab.checkpoint.dto.CheckpointGateView;
import com.devmind.decisionlab.checkpoint.dto.CheckpointRequest;
import com.devmind.decisionlab.checkpoint.dto.CheckpointVerifyRequest;
import com.devmind.decisionlab.checkpoint.dto.CheckpointView;
import com.devmind.decisionlab.checkpoint.dto.ServeCheckResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-56 FR-06/FR-07 模型产物登记与准入 API（{@code /api/decision/checkpoints}）。
 *
 * <p><b>没有 PUT/PATCH</b>：登记信息写错就删掉重建（未验证的行随便删）。可编辑的登记表会让
 * "验证之后换掉权重/来源"变成一次普通的编辑，而 {@code verified_note} 里写的还是老那份的依据——
 * 审计链上必须留下一道清楚的删除+重登记的痕，见 {@code CheckpointService} 的类注释。</p>
 */
@RestController
@RequestMapping("/api/decision/checkpoints")
public class DecisionCheckpointController {

    private final CheckpointService service;

    public DecisionCheckpointController(CheckpointService service) {
        this.service = service;
    }

    @GetMapping
    public PageView<CheckpointView> list(@RequestParam(required = false) String serveSlot,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return service.list(serveSlot, page, size);
    }

    /** 闸门当前状态（前端横幅）：与分诊按钮置灰同一个上游，说法不可能不一致 */
    @GetMapping("/gate")
    public CheckpointGateView gate() {
        return service.gate();
    }

    @PostMapping
    public CheckpointView create(@RequestBody CheckpointRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    public CheckpointDetail get(@PathVariable Long id) {
        return service.detail(id);
    }

    /** 人工确认放行（请求体必须带判断依据）；同槽位其它已放行的产物会被顶掉 */
    @PostMapping("/{id}/verify")
    public CheckpointView verify(@PathVariable Long id, @RequestBody CheckpointVerifyRequest req) {
        return service.verify(id, req);
    }

    /** 撤销放行（必须写原因）；闸门立即关闭 */
    @PostMapping("/{id}/unverify")
    public CheckpointView unverify(@PathVariable Long id, @RequestBody CheckpointVerifyRequest req) {
        return service.unverify(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /** serve 自检：打边车 {@code /healthz} 核对"登记的槽位是不是真的在服务" */
    @PostMapping("/{id}/serve-check")
    public ServeCheckResult serveCheck(@PathVariable Long id) {
        return service.serveCheck(id);
    }
}
