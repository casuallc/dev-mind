package com.devmind.classify.instance.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * CAP-57 FR-02 分类服务实例（{@code classify_instances}）：一行 = 一个受管的分类边车进程，
 * 显式绑定一个 agent 节点（1:1，无路由链——实例就是「那台机器上那个端口的服务」）。
 *
 * <p><b>平台只调度不执行</b>：起停/状态经 WS proc 帧（协议 v15）下发绑定节点，runner 侧
 * {@code ProcessBuilder(argv)} 拉起、pidfile+proc.json 对账；本表记的是「期望与最近观测」，
 * 真实存活以节点 proc 状态与 {@code /healthz} 轮询为准。</p>
 *
 * <p>状态机：STOPPED →（start ack ok）→ STARTING →（healthz 通过）→ RUNNING；
 * RUNNING/超宽限期的 STARTING →（healthz 失败）→ UNHEALTHY（<b>不自动拉起</b>，人工或
 * 「重试启动」）。{@link #lastStartAt} 是宽限期的锚点：GB 权重加载慢，start 后
 * {@code devmind.classify.start-grace-ms}（默认 10min）内 healthz 失败不判 UNHEALTHY。</p>
 */
@Entity
@Table(name = "classify_instances")
public class ClassifyInstanceEntity {

    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_STARTING = "STARTING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_UNHEALTHY = "UNHEALTHY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 实例名（管控台展示与日志标识；唯一） */
    @Column(nullable = false, length = 128, unique = true)
    private String name;

    /** 绑定的 agent 节点 id（字符串口径，与会话 agent_node_id 一致） */
    @Column(name = "agent_node_id", nullable = false, length = 64)
    private String agentNodeId;

    /** 边车监听端口（组 argv 的 {@code --port}） */
    @Column(nullable = false)
    private Integer port;

    /** 服务端打 healthz/playground 用的可达地址（如 {@code http://172.20.140.88:8377}） */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /** 应用包（SIDECAR_APP 类安装包）引用；空 = 未绑定，不能 start（409） */
    @Column(name = "app_package_id")
    private Long appPackageId;

    /** 节点上 python 解释器（相对应用包目录或 PATH 可解析；Windows 示例 venv/Scripts/python.exe） */
    @Column(name = "python_bin", nullable = false, length = 256)
    @ColumnDefault("'venv/bin/python'")
    private String pythonBin = "venv/bin/python";

    /** 环境变量 JSON（{@code Map<String,String>}；值支持 {@code ${PKG_DIR:<packageId>}} 占位符） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "env_json", length = 16_777_216)
    private String envJson;

    /**
     * 命令覆盖（空格分隔的 argv，<b>无 shell 语义</b>——引号/重定向不解析）。
     * 空 = 默认 {@code <pythonBin> -m uvicorn app:app --host 0.0.0.0 --port <port>}。
     */
    @Column(name = "command_override", length = 1024)
    private String commandOverride;

    /** STOPPED / STARTING / RUNNING / UNHEALTHY */
    @Column(nullable = false, length = 16)
    @ColumnDefault("'STOPPED'")
    private String status = STATUS_STOPPED;

    /** 最近一次 start 下发成功的时间（健康宽限期锚点） */
    @Column(name = "last_start_at")
    private Instant lastStartAt;

    @Column(name = "last_health_at")
    private Instant lastHealthAt;

    /** 最近一次 healthz 快照 JSON（status/version/loaded/devices/sources 原样留存供详情页） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "last_health_json", length = 16_777_216)
    private String lastHealthJson;

    /** 最近一次失败原因（healthz 异常消息已脱敏 / proc ack 的 error） */
    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAgentNodeId() { return agentNodeId; }
    public void setAgentNodeId(String agentNodeId) { this.agentNodeId = agentNodeId; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Long getAppPackageId() { return appPackageId; }
    public void setAppPackageId(Long appPackageId) { this.appPackageId = appPackageId; }
    public String getPythonBin() { return pythonBin; }
    public void setPythonBin(String pythonBin) { this.pythonBin = pythonBin; }
    public String getEnvJson() { return envJson; }
    public void setEnvJson(String envJson) { this.envJson = envJson; }
    public String getCommandOverride() { return commandOverride; }
    public void setCommandOverride(String commandOverride) { this.commandOverride = commandOverride; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getLastStartAt() { return lastStartAt; }
    public void setLastStartAt(Instant lastStartAt) { this.lastStartAt = lastStartAt; }
    public Instant getLastHealthAt() { return lastHealthAt; }
    public void setLastHealthAt(Instant lastHealthAt) { this.lastHealthAt = lastHealthAt; }
    public String getLastHealthJson() { return lastHealthJson; }
    public void setLastHealthJson(String lastHealthJson) { this.lastHealthJson = lastHealthJson; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
