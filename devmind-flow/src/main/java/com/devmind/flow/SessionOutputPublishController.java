package com.devmind.flow;

import com.devmind.flow.dto.PublishOutputRequest;
import com.devmind.flow.dto.PublishOutputResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-39 FR-03 会话产出手动推送 REST（会话作用域，与 RequirementFlowController 的
 * 项目作用域分开）：把 session_outputs 中已回传的产出落成/更新为关联需求的文档。
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}/outputs")
public class SessionOutputPublishController {

    private final RequirementFlowService service;

    public SessionOutputPublishController(RequirementFlowService service) {
        this.service = service;
    }

    /** 推送产出为需求文档（create 新建 / update 目标文档新版本；design 同步落 Design 记录）。 */
    @PostMapping("/publish")
    public PublishOutputResult publish(@PathVariable String sessionId, @RequestBody PublishOutputRequest req) {
        return service.publishOutput(sessionId, req);
    }
}
