package com.devmind.agent.controller;

import com.devmind.agent.dto.AgentNodeView;
import com.devmind.agent.dto.CreateAgentNodeRequest;
import com.devmind.agent.dto.IssuedNodeView;
import com.devmind.agent.dto.RunnerPackageView;
import com.devmind.agent.dto.UpgradeResultView;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.model.RunnerPackageEntity;
import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.agent.service.RunnerPackageService;
import com.devmind.auth.IdentityService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * CAP-21 节点管理端点（/api/agent-nodes）。创建返回的 token 仅此一次可见。
 * 变更操作在 SecurityConfig 限定 ADMIN。
 *
 * <p>FR-09 runner 包：/runner-package 上传/元数据/下载 + /{id}/upgrade 手动升级。
 * 下载端点在 SecurityConfig permitAll，此处在控制器内做「节点 token 或登录态」双认证。</p>
 */
@RestController
@RequestMapping("/api/agent-nodes")
public class AgentNodeController {

    private final AgentNodeService service;
    private final AgentConnectionRegistry registry;
    private final RunnerPackageService packageService;
    private final IdentityService identityService;

    public AgentNodeController(AgentNodeService service, AgentConnectionRegistry registry,
                               RunnerPackageService packageService, IdentityService identityService) {
        this.service = service;
        this.registry = registry;
        this.packageService = packageService;
        this.identityService = identityService;
    }

    @PostMapping
    public IssuedNodeView create(@Valid @RequestBody CreateAgentNodeRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<AgentNodeView> list() {
        return service.list();
    }

    @PostMapping("/{id}/disable")
    public AgentNodeView disable(@PathVariable Long id) {
        AgentNodeView view = service.setDisabled(id, true);
        registry.evict(id);
        return view;
    }

    @PostMapping("/{id}/enable")
    public AgentNodeView enable(@PathVariable Long id) {
        return service.setDisabled(id, false);
    }

    /** 设为平台默认执行节点（FR-03）：会话/项目未指定节点时的最终远程兜底，全平台至多一个。 */
    @PostMapping("/{id}/default")
    public AgentNodeView setDefault(@PathVariable Long id) {
        return service.setDefault(id, true);
    }

    /** 取消平台默认执行节点：之后未指定节点的会话回落本机。 */
    @PostMapping("/{id}/unset-default")
    public AgentNodeView unsetDefault(@PathVariable Long id) {
        return service.setDefault(id, false);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        registry.evict(id);
        service.delete(id);
    }

    // ---------------- FR-09 runner 包托管与手动升级 ----------------

    /** 上传替换当前 runner 包（全局单份）。 */
    @PostMapping(value = "/runner-package", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RunnerPackageView uploadPackage(@RequestParam("file") MultipartFile file) throws IOException {
        return packageService.upload(file, identityService.currentActor());
    }

    @GetMapping("/runner-package")
    public RunnerPackageView currentPackage() {
        return packageService.current();
    }

    /** jar 下载：节点 token（?token=，runner 升级用）或 JWT 登录态（管理页下载）。 */
    @GetMapping("/runner-package/download")
    public ResponseEntity<Resource> downloadPackage(@RequestParam(required = false) String token) {
        if (!isNodeToken(token) && !isLoggedIn()) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "下载 runner 包需要节点 token 或登录态");
        }
        RunnerPackageView meta = packageService.current();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/java-archive"))
                .contentLength(meta.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"devmind-agent-runner.jar\"")
                .body(packageService.openForDownload());
    }

    /**
     * 手动升级指定节点到当前托管包版本。恒 200，业务结果看 status：
     * ACCEPTED / BUSY（活跃会话推迟）/ ALREADY_LATEST / REJECTED。
     */
    @PostMapping("/{id}/upgrade")
    public UpgradeResultView upgrade(@PathVariable Long id) {
        AgentNodeEntity node = service.require(id);
        if (!registry.isOnline(String.valueOf(id))) {
            throw new DevMindException(ErrorCode.CONFLICT, "节点不在线: " + node.getName());
        }
        RunnerPackageEntity pkg = packageService.currentOpt()
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT, "尚未上传 runner 包"));
        if (pkg.getVersion().equals(node.getRunnerVersion())) {
            return new UpgradeResultView("ALREADY_LATEST",
                    "节点已是当前版本 " + pkg.getVersion() + "，无需升级", null);
        }
        AgentConnectionRegistry.UpgradeAck ack =
                registry.sendUpgrade(id, pkg.getVersion(), pkg.getSha256(), pkg.getSizeBytes());
        if (ack.ok()) {
            return new UpgradeResultView("ACCEPTED", "升级指令已下发，节点将自动重启到新版本", null);
        }
        if ("busy".equals(ack.reason())) {
            return new UpgradeResultView("BUSY",
                    "节点有 " + ack.activeSessions() + " 个活跃会话，已推迟升级", ack.activeSessions());
        }
        if ("disconnect".equals(ack.reason())) {
            return new UpgradeResultView("REJECTED", "升级过程中节点断线（可能正在重启，请稍后核对版本）", null);
        }
        return new UpgradeResultView("REJECTED", "节点拒绝升级: " + ack.reason(), null);
    }

    private boolean isNodeToken(String token) {
        return service.resolveByToken(token).isPresent();
    }

    /** JwtAuthFilter 对任何路径都会在 Bearer 合法时填充 SecurityContext，此处只读判定。 */
    private boolean isLoggedIn() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}
