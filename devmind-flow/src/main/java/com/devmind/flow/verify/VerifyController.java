package com.devmind.flow.verify;

import com.devmind.flow.verify.dto.VerifyRunView;
import com.devmind.flow.verify.dto.VerifyTriggerRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CAP-73 需求开发中验证 REST。
 *
 * <p>人工入口走项目作用域（{@code POST /api/projects/{pid}/requirements/{rid}/verify}，
 * 登录态）；agent 入口走 {@code POST /api/verify/trigger?token=}（verify 作用域 token
 * 自证，SecurityConfig permitAll + 服务内验签 + 会话存活检查）。</p>
 */
@RestController
@RequestMapping("/api")
public class VerifyController {

    private final VerifyService service;

    public VerifyController(VerifyService service) {
        this.service = service;
    }

    /** 人工触发「立即验证」；force=true 跳过无新提交判定（修复环境后重试用）。 */
    @PostMapping("/projects/{projectId}/requirements/{requirementId}/verify")
    public VerifyRunView triggerManual(@PathVariable String projectId,
                                       @PathVariable String requirementId,
                                       @RequestParam(defaultValue = "false") boolean force,
                                       @RequestBody(required = false) VerifyTriggerRequest req) {
        return service.triggerManual(projectId, requirementId, force, req);
    }

    /** 需求的验证 run 列表（新到旧；不含日志全文） */
    @GetMapping("/projects/{projectId}/requirements/{requirementId}/verify-runs")
    public List<VerifyRunView> listRuns(@PathVariable String projectId,
                                        @PathVariable String requirementId) {
        return service.listRuns(projectId, requirementId);
    }

    /** 单个 run 详情（含全链日志） */
    @GetMapping("/verify/runs/{id}")
    public VerifyRunView getRun(@PathVariable long id) {
        return service.getRun(id);
    }

    /** agent 通道：devmind-verify 助手脚本携带 verify token 触发（permitAll + 服务内验签） */
    @PostMapping("/verify/trigger")
    public VerifyRunView triggerAgent(@RequestParam String token,
                                      @RequestBody(required = false) VerifyTriggerRequest req) {
        return service.triggerAgent(token, req);
    }
}
