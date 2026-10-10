package com.devmind.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * agent_audit_logs 表：agent 节点域全量交互审计（CAP-71，演进自 agent_conn_logs 连接流水）。
 * 九分类：CONNECTION（控制通道）/ TUNNEL（隧道通道）/ COMMAND（下行指令）/ PROXY（代理转发逐条）/
 * CONTEXT（上下文包）/ FILE_TRANSFER（文件中转）/ RULE（出口规则）/ PACKAGE（runner 包）/ NODE（节点运维）。
 *
 * <p>nodeName 做快照（节点删除后日志仍可读）；REJECT / 代理未命中规则时 nodeId 为空。
 * detail/target 保持普通 varchar——关键词检索要 lower like，禁升级 @Lob（lower 红线）。</p>
 */
@Entity
@Table(name = "agent_audit_logs", indexes = {
        @Index(name = "idx_audit_created", columnList = "created_at"),
        @Index(name = "idx_audit_category_created", columnList = "category,created_at"),
        @Index(name = "idx_audit_node_created", columnList = "node_id,created_at")
})
public class AgentAuditLogEntity {

    // ---------------- category ----------------
    /** 控制通道（/ws/agent）起落与主动踢除 */
    public static final String CAT_CONNECTION = "CONNECTION";
    /** 隧道通道（/ws/agent-tunnel）起落/静默断链/非法帧 */
    public static final String CAT_TUNNEL = "TUNNEL";
    /** 下行指令（launch/exec/terminal/file/upgrade 等） */
    public static final String CAT_COMMAND = "COMMAND";
    /** 代理转发逐条（CAP-70 出口隧道） */
    public static final String CAT_PROXY = "PROXY";
    /** 上下文包拉取 */
    public static final String CAT_CONTEXT = "CONTEXT";
    /** CAP-65 文件中转 */
    public static final String CAT_FILE_TRANSFER = "FILE_TRANSFER";
    /** 出口规则变更 */
    public static final String CAT_RULE = "RULE";
    /** runner 包上传/下载 */
    public static final String CAT_PACKAGE = "PACKAGE";
    /** 节点运维（创建/禁用/删除等） */
    public static final String CAT_NODE = "NODE";

    // ---------------- result ----------------
    public static final String RESULT_OK = "OK";
    public static final String RESULT_FAIL = "FAIL";
    public static final String RESULT_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 九分类（CAT_* 常量） */
    @Column(length = 16, nullable = false)
    private String category;

    /** 分类内事件（CONNECT/LAUNCH/OPEN/…） */
    @Column(length = 32, nullable = false)
    private String event;

    /** 关联节点（REJECT / 代理未命中规则时为空） */
    @Column(name = "node_id")
    private Long nodeId;

    /** 节点名快照 */
    @Column(name = "node_name", length = 128)
    private String nodeName;

    /** 管理操作人（RULE/PACKAGE/NODE 类），WS 交互为空 */
    @Column(length = 64)
    private String actor;

    /** WS 来源地址（IP:端口） */
    @Column(name = "remote_addr", length = 64)
    private String remoteAddr;

    /** 目标：代理 host:port / sessionId / transferId / 规则 pattern */
    @Column(length = 255)
    private String target;

    /** OK / FAIL / REJECTED */
    @Column(length = 16)
    private String result;

    /** 代理流上行字节数 */
    @Column(name = "bytes_up")
    private Long bytesUp;

    /** 代理流下行字节数 */
    @Column(name = "bytes_down")
    private Long bytesDown;

    /** 代理流存活 / 带 ack 指令等待耗时（毫秒） */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** 摘要（拒绝原因/错误文案/exitCode 等；禁凭证，INPUT 只记 len/images 计数） */
    @Column(length = 512)
    private String detail;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getRemoteAddr() { return remoteAddr; }
    public void setRemoteAddr(String remoteAddr) { this.remoteAddr = remoteAddr; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Long getBytesUp() { return bytesUp; }
    public void setBytesUp(Long bytesUp) { this.bytesUp = bytesUp; }
    public Long getBytesDown() { return bytesDown; }
    public void setBytesDown(Long bytesDown) { this.bytesDown = bytesDown; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
