package com.devmind.serveradapter.controller;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.ServerView;
import com.devmind.project.model.ProjectServerEntity;
import com.devmind.project.repo.ProjectServerRepository;
import com.devmind.serveradapter.dto.AuditView;
import com.devmind.serveradapter.dto.ExecuteRequest;
import com.devmind.serveradapter.dto.HealthCheckRequest;
import com.devmind.common.audit.AuditLogEntity;
import com.devmind.common.audit.AuditLogRepository;
import com.devmind.serveradapter.service.ServerOperationService;
import com.devmind.serveradapter.spi.ConnectResult;
import com.devmind.serveradapter.spi.ExecResult;
import com.devmind.serveradapter.spi.HealthCheckConfig;
import com.devmind.serveradapter.spi.HealthResult;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CAP-07 服务器运维 API（CAP-02 已提供 /api/projects/{id}/servers 的 CRUD，本控制器做远程操作）。
 */
@RestController
@RequestMapping("/api/servers")
public class ServerOperationController {

    private final ServerOperationService ops;
    private final ProjectServerRepository serverRepo;
    private final AuditLogRepository auditRepo;

    public ServerOperationController(ServerOperationService ops,
                                     ProjectServerRepository serverRepo,
                                     AuditLogRepository auditRepo) {
        this.ops = ops;
        this.serverRepo = serverRepo;
        this.auditRepo = auditRepo;
    }

    /** 跨项目服务器列表（不含连接配置，运维页用） */
    @GetMapping
    public List<ServerListItem> listAll() {
        return ops.listAll().stream()
                .map(e -> new ServerListItem(e.getId(), e.getProjectId(), e.getName(), e.getEnv(),
                        e.getAccessType(), split(e.getCapabilities()), e.getEnabled()))
                .toList();
    }

    /** FR-02 连通性测试 */
    @PostMapping("/{id}/test")
    public ConnectResult test(@PathVariable Long id) {
        return ops.connectTest(id);
    }

    /** FR-02/FR-05 执行模板（白名单） */
    @PostMapping("/{id}/execute")
    public ExecResult execute(@PathVariable Long id, @Valid @RequestBody ExecuteRequest req) {
        return ops.execute(id, req.templateCode(), req.params(), req.capability());
    }

    /** FR-02 文件上传（multipart: file + remotePath） */
    @PostMapping(value = "/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult upload(@PathVariable Long id,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam("remotePath") String remotePath) {
        if (file.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "file 不能为空");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("devmind-upload-", ".bin");
            file.transferTo(tmp.toAbsolutePath());
            ops.upload(id, tmp.toString(), remotePath);
            return new UploadResult(true, "已上传到 " + remotePath);
        } catch (DevMindException e) {
            throw e;
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "上传失败: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
            }
        }
    }

    /** FR-02 文件下载（文本） */
    @GetMapping(value = "/{id}/download", produces = MediaType.TEXT_PLAIN_VALUE)
    public String download(@PathVariable Long id, @RequestParam("path") String remotePath) {
        return ops.download(id, remotePath);
    }

    /** FR-02 健康检查 */
    @PostMapping("/{id}/health")
    public HealthResult health(@PathVariable Long id, @RequestBody(required = false) HealthCheckRequest req) {
        HealthCheckConfig cfg = req == null ? null
                : "http".equalsIgnoreCase(req.type()) ? HealthCheckConfig.http(req.url(), req.expectedStatus())
                : "command".equalsIgnoreCase(req.type()) ? HealthCheckConfig.command(req.command())
                : null;
        if (cfg == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "type 必须为 http 或 command");
        }
        return ops.healthCheck(id, cfg);
    }

    /** FR-02 拉取日志（走 logs 模板白名单） */
    @GetMapping("/{id}/logs")
    public ExecResult logs(@PathVariable Long id,
                           @RequestParam(value = "template", required = false) String template) {
        return ops.logs(id, template);
    }

    /** FR-07 验收：原始（密文）存储配置 + 敏感字段加密状态 */
    @GetMapping("/{id}/stored-config")
    public ServerOperationService.StoredConfigView storedConfig(@PathVariable Long id) {
        return ops.storedConfig(id);
    }

    /** FR-06 该服务器审计记录 */
    @GetMapping("/{id}/audit")
    public List<AuditView> audit(@PathVariable Long id,
                                 @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return auditRepo.findByServerIdOrderByIdDesc(id, PageRequest.of(0, Math.min(limit, 200)))
                .stream().map(this::toAuditView).toList();
    }

    private AuditView toAuditView(AuditLogEntity a) {
        return new AuditView(a.getId(), a.getProjectId(), a.getServerId(), a.getServerName(), a.getAccessType(),
                a.getAction(), a.getTemplateCode(), a.getCapability(), a.getCommand(), a.getExitCode(),
                Boolean.TRUE.equals(a.getSuccess()), a.getDetail(), a.getDurationMs(), a.getCreatedAt());
    }

    private List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 服务器列表项（无连接配置） */
    public record ServerListItem(Long id, String projectId, String name, String env,
                                 String accessType, List<String> capabilities, Boolean enabled) {
    }

    /** 上传结果 */
    public record UploadResult(boolean ok, String message) {
    }
}
