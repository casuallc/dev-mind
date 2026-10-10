package com.devmind.agent.controller;

import com.devmind.agent.dto.AgentAuditLogView;
import com.devmind.agent.dto.AgentNodeView;
import com.devmind.agent.dto.CreateAgentNodeRequest;
import com.devmind.agent.dto.IssuedNodeView;
import com.devmind.agent.dto.RunnerPackageView;
import com.devmind.agent.dto.UpgradeResultView;
import com.devmind.agent.model.AgentAuditLogEntity;
import com.devmind.agent.model.AgentNodeEntity;
import com.devmind.agent.model.RunnerPackageEntity;
import com.devmind.agent.registry.AgentConnectionRegistry;
import com.devmind.agent.service.AgentAuditLogService;
import com.devmind.agent.service.AgentNodeService;
import com.devmind.agent.service.RunnerPackageService;
import com.devmind.auth.IdentityService;
import com.devmind.common.agent.AgentNodeSessionsProvider;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
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
    private final AgentAuditLogService auditLogService;
    private final IdentityService identityService;
    /** 会话持有方（session/chat 模块）的活跃会话 SPI，Spring 聚合注入，无实现时为空表 */
    private final List<AgentNodeSessionsProvider> sessionsProviders;

    public AgentNodeController(AgentNodeService service, AgentConnectionRegistry registry,
                               RunnerPackageService packageService, AgentAuditLogService auditLogService,
                               IdentityService identityService,
                               List<AgentNodeSessionsProvider> sessionsProviders) {
        this.service = service;
        this.registry = registry;
        this.packageService = packageService;
        this.auditLogService = auditLogService;
        this.identityService = identityService;
        this.sessionsProviders = sessionsProviders;
    }

    @PostMapping
    public IssuedNodeView create(@Valid @RequestBody CreateAgentNodeRequest req) {
        IssuedNodeView v = service.create(req);
        auditNode("CREATE", v.node().id(), v.node().name(), null);
        return v;
    }

    /** 节点列表：逐节点聚合各会话模块 SPI 得活跃会话数（节点数为个位数，逐点查询可接受）。 */
    @GetMapping
    public List<AgentNodeView> list() {
        return service.list().stream()
                .map(v -> v.withActiveSessionCount(countActiveSessions(v.id())))
                .toList();
    }

    private int countActiveSessions(Long nodeId) {
        return sessionsProviders.stream()
                .mapToInt(p -> p.activeSessions(String.valueOf(nodeId)).size())
                .sum();
    }

    /**
     * CAP-71 节点审计日志（替代旧 /conn-logs 流水）：全量交互分类留痕，
     * 服务端真分页 + category/event/nodeId/result/keyword 过滤（page 0 起、size≤200）。
     */
    @GetMapping("/audit-logs")
    public PageView<AgentAuditLogView> auditLogs(@RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String event,
                                                 @RequestParam(required = false) Long nodeId,
                                                 @RequestParam(required = false) String result,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        Page<AgentAuditLogEntity> data = auditLogService.search(category, event, nodeId, result,
                keyword, page, size);
        return new PageView<>(data.getContent().stream().map(AgentAuditLogView::from).toList(),
                data.getTotalElements(), data.getNumber(), data.getSize());
    }

    @PostMapping("/{id}/disable")
    public AgentNodeView disable(@PathVariable Long id) {
        AgentNodeView view = service.setDisabled(id, true);
        registry.evict(id);
        auditNode("DISABLE", view.id(), view.name(), null);
        return view;
    }

    @PostMapping("/{id}/enable")
    public AgentNodeView enable(@PathVariable Long id) {
        AgentNodeView view = service.setDisabled(id, false);
        auditNode("ENABLE", view.id(), view.name(), null);
        return view;
    }

    /** FR-07 编辑节点标签（runner 配置非空 labels 时会被其 hello 覆盖）+ CAP-43 外网代理（proxyUrl/proxyScopes）。 */
    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    public AgentNodeView update(@PathVariable Long id, @RequestBody com.devmind.agent.dto.UpdateAgentNodeRequest req) {
        AgentNodeView view = service.update(id, req);
        auditNode("UPDATE", view.id(), view.name(),
                "labels=" + (req.labels() != null ? "改" : "不动")
                        + " proxy=" + (req.proxyUrl() != null ? "改" : "不动")
                        + " fileRoots=" + (req.fileRoots() != null ? "改" : "不动"));
        return view;
    }

    /** 设为平台默认执行节点（FR-03）：会话/项目未指定节点时的最终远程兜底，全平台至多一个。 */
    @PostMapping("/{id}/default")
    public AgentNodeView setDefault(@PathVariable Long id) {
        AgentNodeView view = service.setDefault(id, true);
        auditNode("SET_DEFAULT", view.id(), view.name(), null);
        return view;
    }

    /** 取消平台默认执行节点：之后未指定节点的会话回落本机。 */
    @PostMapping("/{id}/unset-default")
    public AgentNodeView unsetDefault(@PathVariable Long id) {
        AgentNodeView view = service.setDefault(id, false);
        auditNode("UNSET_DEFAULT", view.id(), view.name(), null);
        return view;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        registry.evict(id);
        service.delete(id);
        auditNode("DELETE", id, null, null);
    }

    /** CAP-71：节点运维审计（NODE 分类，带操作人）。 */
    private void auditNode(String event, Long nodeId, String nodeName, String detail) {
        auditLogService.record(AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_NODE, event)
                .actor(identityService.currentActor())
                .nodeId(nodeId).nodeName(nodeName)
                .result(AgentAuditLogEntity.RESULT_OK).detail(detail));
    }

    /**
     * 节点上的活跃会话清单（开发会话 + 通用问答，活动三态 RUNNING/WAITING_INPUT/WAITING_AUTH）。
     * 强制升级前展示「会终止哪些会话」；数据来自各会话模块的 AgentNodeSessionsProvider SPI，
     * 未装配任何实现时为空表。
     */
    @GetMapping("/{id}/active-sessions")
    public List<AgentNodeSessionsProvider.ActiveSessionInfo> activeSessions(@PathVariable Long id) {
        service.require(id); // 节点不存在 → 404
        return sessionsProviders.stream()
                .flatMap(p -> p.activeSessions(String.valueOf(id)).stream())
                .toList();
    }

    // ---------------- FR-09 runner 包托管与手动升级 ----------------

    /** 上传替换当前 runner 包（全局单份）。旧构建覆盖新构建 → 409；?force=true 确认降级。 */
    @PostMapping(value = "/runner-package", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RunnerPackageView uploadPackage(@RequestParam("file") MultipartFile file,
                                           @RequestParam(defaultValue = "false") boolean force) throws IOException {
        return packageService.upload(file, identityService.currentActor(), force);
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
        // CAP-71：包下载审计——节点 token 下载记节点，登录态下载记操作人
        var nodeOpt = service.resolveByToken(token);
        var entry = AgentAuditLogService.Entry.of(AgentAuditLogEntity.CAT_PACKAGE, "DOWNLOAD")
                .target(meta.version()).result(AgentAuditLogEntity.RESULT_OK)
                .bytes(0, meta.sizeBytes());
        entry = nodeOpt.isPresent() ? entry.node(nodeOpt.get())
                : entry.actor(identityService.currentActor());
        auditLogService.record(entry);
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
     * force=true：runner 先终止全部活跃会话（正常 kill → exit 帧 → 托管工作区收尾）再升级；
     * 旧 runner 不认识 force 字段仍回 busy。
     */
    @PostMapping("/{id}/upgrade")
    public UpgradeResultView upgrade(@PathVariable Long id,
                                     @RequestParam(defaultValue = "false") boolean force) {
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
                registry.sendUpgrade(id, pkg.getVersion(), pkg.getSha256(), pkg.getSizeBytes(), force);
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
