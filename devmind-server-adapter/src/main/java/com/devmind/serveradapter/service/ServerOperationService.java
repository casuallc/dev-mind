package com.devmind.serveradapter.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.model.ProjectServerEntity;
import com.devmind.project.repo.ProjectServerRepository;
import com.devmind.serveradapter.config.CredentialCrypto;
import com.devmind.serveradapter.config.ServerAdapterProperties;
import com.devmind.common.audit.AuditLogEntity;
import com.devmind.serveradapter.model.ScriptTemplateEntity;
import com.devmind.common.audit.AuditLogRepository;
import com.devmind.serveradapter.repo.ScriptTemplateRepository;
import com.devmind.serveradapter.registry.ServerAdapterRegistry;
import com.devmind.serveradapter.spi.ConnectResult;
import com.devmind.serveradapter.spi.ExecResult;
import com.devmind.serveradapter.spi.HealthCheckConfig;
import com.devmind.serveradapter.spi.HealthResult;
import com.devmind.serveradapter.spi.ScriptTemplate;
import com.devmind.serveradapter.spi.ServerTarget;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CAP-07 编排层：加载服务器 → 解密凭证 → 解析/校验模板（白名单）→ 路由适配器 → 全量审计。
 * 上层（CAP-08/09/10/11）只依赖本服务与 {@link ServerAdapter} SPI。
 */
@Service
public class ServerOperationService {

    private static final Logger log = LoggerFactory.getLogger(ServerOperationService.class);
    private static final String DEFAULT_CAPABILITY = "exec";

    private final ProjectServerRepository serverRepo;
    private final ScriptTemplateRepository templateRepo;
    private final AuditLogRepository auditRepo;
    private final ServerAdapterRegistry registry;
    private final CredentialCrypto crypto;
    private final ServerAdapterProperties props;
    private final ObjectMapper mapper;

    public ServerOperationService(ProjectServerRepository serverRepo,
                                  ScriptTemplateRepository templateRepo,
                                  AuditLogRepository auditRepo,
                                  ServerAdapterRegistry registry,
                                  CredentialCrypto crypto,
                                  ServerAdapterProperties props,
                                  ObjectMapper mapper) {
        this.serverRepo = serverRepo;
        this.templateRepo = templateRepo;
        this.auditRepo = auditRepo;
        this.registry = registry;
        this.crypto = crypto;
        this.props = props;
        this.mapper = mapper;
    }

    /** 跨项目服务器列表（运维页用） */
    public List<ProjectServerEntity> listAll() {
        return serverRepo.findAll().stream()
                .sorted((a, b) -> a.getProjectId().compareTo(b.getProjectId()))
                .toList();
    }

    public ProjectServerEntity requireServer(Long id) {
        return serverRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "服务器不存在: " + id));
    }

    public ConnectResult connectTest(Long serverId) {
        ProjectServerEntity e = requireServer(serverId);
        long start = System.currentTimeMillis();
        ConnectResult r = registry.require(e.getAccessType()).connectTest(target(e), props.getConnectTimeoutMs());
        audit(e, "connect_test", null, null, null, r.ok(), r.message(), System.currentTimeMillis() - start);
        return r;
    }

    public ExecResult execute(Long serverId, String templateCode, Map<String, String> params, String capability) {
        ProjectServerEntity e = requireServer(serverId);
        String cap = capability == null || capability.isBlank() ? DEFAULT_CAPABILITY : capability;
        ScriptTemplate tpl = requireTemplate(e, templateCode);
        checkCapability(e, tpl, cap);
        checkRequiredParams(tpl, params);
        String rendered = tpl.render(params);
        long start = System.currentTimeMillis();
        ExecResult r = registry.require(e.getAccessType()).execute(target(e), rendered, props.getConnectTimeoutMs());
        audit(e, "execute", templateCode, cap, rendered, r.success(), summary(r.stdout(), r.stderr()), r.durationMs());
        return r;
    }

    public void upload(Long serverId, String localPath, String remotePath) {
        ProjectServerEntity e = requireServer(serverId);
        long start = System.currentTimeMillis();
        try {
            registry.require(e.getAccessType()).upload(target(e), localPath, remotePath, props.getConnectTimeoutMs());
            audit(e, "upload", null, null, "上传 " + localPath + " → " + remotePath, true, "ok", System.currentTimeMillis() - start);
        } catch (Exception ex) {
            audit(e, "upload", null, null, "上传 " + localPath + " → " + remotePath, false, rootMessage(ex), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    public String download(Long serverId, String remotePath) {
        ProjectServerEntity e = requireServer(serverId);
        long start = System.currentTimeMillis();
        String content = registry.require(e.getAccessType()).download(target(e), remotePath, props.getConnectTimeoutMs());
        audit(e, "download", null, null, "下载 " + remotePath, true, "len=" + content.length(), System.currentTimeMillis() - start);
        return content;
    }

    public HealthResult healthCheck(Long serverId, HealthCheckConfig cfg) {
        ProjectServerEntity e = requireServer(serverId);
        long start = System.currentTimeMillis();
        HealthResult r = registry.require(e.getAccessType()).healthCheck(target(e), cfg, props.getConnectTimeoutMs());
        audit(e, "health_check", null, "health", "type=" + (cfg == null ? "?" : cfg.type()), r.ok(), r.message(), System.currentTimeMillis() - start);
        return r;
    }

    public ExecResult logs(Long serverId, String templateCode) {
        String code = templateCode == null || templateCode.isBlank() ? "logs" : templateCode;
        return execute(serverId, code, Map.of(), "logs");
    }

    /** 原始（密文）配置 + 敏感字段加密状态（FR-07 验收用；单用户本地工具可接受） */
    public StoredConfigView storedConfig(Long serverId) {
        ProjectServerEntity e = requireServer(serverId);
        List<FieldState> fields = new ArrayList<>();
        if (e.getAccessConfig() != null && !e.getAccessConfig().isBlank()) {
            try {
                JsonNode node = mapper.readTree(e.getAccessConfig());
                for (java.util.Map.Entry<String, JsonNode> entry : ((tools.jackson.databind.node.ObjectNode) node).properties()) {
                    JsonNode v = entry.getValue();
                    if (v.isTextual()) {
                        fields.add(new FieldState(entry.getKey(), crypto.isEncrypted(v.asText())));
                    }
                }
            } catch (Exception ex) {
                // 非 JSON 配置无法判定
            }
        }
        return new StoredConfigView(e.getAccessConfig(), fields);
    }

    // ---------- 模板 ----------

    public ScriptTemplateEntity requireTemplateEntity(Long id) {
        return templateRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "脚本模板不存在: " + id));
    }

    public ScriptTemplate toDomain(ScriptTemplateEntity e) {
        return new ScriptTemplate(e.getId(), e.getProjectId(), e.getCode(), e.getName(), e.getTemplateText(),
                parseParams(e.getParamsSchema()), splitAllowed(e.getAllowed()));
    }

    private ScriptTemplate requireTemplate(ProjectServerEntity server, String templateCode) {
        if (templateCode == null || templateCode.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "必须指定模板 code（白名单）");
        }
        return templateRepo.findByProjectIdAndCode(server.getProjectId(), templateCode)
                .map(this::toDomain)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "项目 " + server.getProjectId() + " 无模板 " + templateCode + "（白名单外命令不可执行）"));
    }

    private void checkRequiredParams(ScriptTemplate tpl, Map<String, String> params) {
        for (ScriptTemplate.ParamSpec p : tpl.params()) {
            if (!p.required()) {
                continue;
            }
            String v = params != null ? params.get(p.name()) : null;
            boolean hasDefault = p.defaultValue() != null && !p.defaultValue().isBlank();
            if ((v == null || v.isBlank()) && !hasDefault) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "模板 " + tpl.code() + " 缺少必填参数: " + p.name());
            }
        }
    }

    private void checkCapability(ProjectServerEntity server, ScriptTemplate tpl, String cap) {
        Set<String> serverCaps = splitCaps(server.getCapabilities());
        if (!serverCaps.isEmpty() && !serverCaps.contains(cap)) {
            throw new DevMindException(ErrorCode.FORBIDDEN,
                    "服务器不具备能力 " + cap + "（可执行能力: " + String.join("/", serverCaps) + "）");
        }
        if (!tpl.allows(cap)) {
            throw new DevMindException(ErrorCode.FORBIDDEN,
                    "模板 " + tpl.code() + " 不允许能力 " + cap + "（允许: "
                            + (tpl.allowed().isEmpty() ? "全部" : String.join("/", tpl.allowed())) + "）");
        }
    }

    private List<ScriptTemplate.ParamSpec> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = mapper.readTree(json);
            List<ScriptTemplate.ParamSpec> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    out.add(new ScriptTemplate.ParamSpec(
                            text(n, "name"), n.path("required").asBoolean(false),
                            text(n, "label"), text(n, "defaultValue")));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Set<String> splitAllowed(String allowed) {
        return splitCaps(allowed);
    }

    private Set<String> splitCaps(String csv) {
        Set<String> set = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return set;
        }
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                set.add(s.trim().toLowerCase());
            }
        }
        return set;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    // ---------- 目标 / 审计 ----------

    private ServerTarget target(ProjectServerEntity e) {
        Map<String, Object> config = Map.of();
        String cfg = e.getAccessConfig();
        if (cfg != null && !cfg.isBlank()) {
            try {
                String decrypted = crypto.decryptConfigJson(cfg);
                JsonNode node = mapper.readTree(decrypted);
                config = mapper.convertValue(node, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            } catch (Exception ex) {
                log.warn("服务器 {} 配置解析失败（按空配置处理）: {}", e.getId(), rootMessage(ex));
            }
        }
        return new ServerTarget(e.getId(), e.getProjectId(), e.getName(), e.getEnv(), e.getAccessType(), config);
    }

    private void audit(ProjectServerEntity server, String action, String templateCode, String capability,
                       String command, boolean success, String detail, long durationMs) {
        if (!props.isAuditEnabled()) {
            return;
        }
        AuditLogEntity a = new AuditLogEntity();
        a.setDomain("server");
        a.setProjectId(server.getProjectId());
        a.setServerId(server.getId());
        a.setServerName(server.getName());
        a.setAccessType(server.getAccessType());
        a.setAction(action);
        a.setTemplateCode(templateCode);
        a.setCapability(capability);
        a.setCommand(truncate(command, 4000));
        a.setExitCode(success ? 0 : -1);
        a.setSuccess(success);
        a.setDetail(truncate(detail, 2000));
        a.setDurationMs(durationMs);
        a.setCreatedAt(Instant.now());
        auditRepo.save(a);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }

    private String summary(String stdout, String stderr) {
        String s = (stdout == null ? "" : stdout) + (stderr == null || stderr.isBlank() ? "" : "\n[stderr]\n" + stderr);
        String trimmed = s.trim();
        return trimmed.isEmpty() ? "(无输出)" : truncate(trimmed, 2000);
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }

    /** stored-config 响应 */
    public record StoredConfigView(String accessConfig, List<FieldState> fields) {
    }

    public record FieldState(String field, boolean encrypted) {
    }
}
