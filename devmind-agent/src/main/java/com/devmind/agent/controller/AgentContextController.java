package com.devmind.agent.controller;

import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.common.agent.ContextPackageProvider;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-34 FR-03 上下文包拉取端点：runner 收 launch 帧 contextManifest 后凭节点 token
 * （?token=，与 runner 包下载同通道先例）HTTP 拉取 {@link ContextPackage}。
 * 包由 session 模块经 common 的 {@link ContextPackageProvider} SPI 供给，agent 模块不反向依赖。
 *
 * <p>SecurityConfig 对 GET /api/agent/context/** permitAll，此处在控制器内做节点 token 认证。</p>
 */
@RestController
@RequestMapping("/api/agent/context")
public class AgentContextController {

    private final AgentNodeService nodeService;
    private final ObjectProvider<ContextPackageProvider> packageProvider;
    private final AgentAuditLogService auditLogService;

    public AgentContextController(AgentNodeService nodeService,
                                  ObjectProvider<ContextPackageProvider> packageProvider,
                                  AgentAuditLogService auditLogService) {
        this.nodeService = nodeService;
        this.packageProvider = packageProvider;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<byte[]> pull(@PathVariable String sessionId,
                                       @RequestParam(required = false) String token) {
        var nodeOpt = nodeService.resolveByToken(token);
        if (nodeOpt.isEmpty()) {
            auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONTEXT, "PULL")
                    .target(sessionId).result(AgentAuditLogEntity.RESULT_REJECTED)
                    .detail("节点 token 无效"));
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "拉取上下文包需要有效节点 token");
        }
        AgentNodeEntity node = nodeOpt.get();
        ContextPackageProvider provider = packageProvider.getIfAvailable();
        if (provider == null) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "上下文包服务未装配");
        }
        try {
            ContextPackage pkg = provider.find(sessionId)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                            "会话无上下文包: " + sessionId));
            byte[] body = ContextPackages.toJsonBytes(pkg);
            auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONTEXT, "PULL")
                    .node(node).target(sessionId).result(AgentAuditLogEntity.RESULT_OK)
                    .bytes(0, body.length));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(body.length)
                    .body(body);
        } catch (DevMindException e) {
            auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_CONTEXT, "PULL")
                    .node(node).target(sessionId).result(AgentAuditLogEntity.RESULT_FAIL)
                    .detail(e.getMessage()));
            throw e;
        }
    }
}
