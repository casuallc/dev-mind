package com.devmind.agent.controller;

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

    public AgentContextController(AgentNodeService nodeService,
                                  ObjectProvider<ContextPackageProvider> packageProvider) {
        this.nodeService = nodeService;
        this.packageProvider = packageProvider;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<byte[]> pull(@PathVariable String sessionId,
                                       @RequestParam(required = false) String token) {
        if (nodeService.resolveByToken(token).isEmpty()) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "拉取上下文包需要有效节点 token");
        }
        ContextPackageProvider provider = packageProvider.getIfAvailable();
        if (provider == null) {
            throw new DevMindException(ErrorCode.NOT_FOUND, "上下文包服务未装配");
        }
        ContextPackage pkg = provider.find(sessionId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "会话无上下文包: " + sessionId));
        byte[] body = ContextPackages.toJsonBytes(pkg);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body);
    }
}
