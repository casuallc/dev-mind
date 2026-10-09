package com.devmind.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * CAP-70 FR-03：egress_rules 平台级出口规则表（服务端 DB 权威，即白名单）。
 * host glob（{@code gitlab.corp.com} / {@code *.corp.com}，小写规范化存储、不区分端口）
 * → 出口节点（弱关联 agent_nodes.id）；未命中任何规则的 host = 直连（零行为变化）。
 * 仅 ADMIN 可配（SecurityConfig 全方法收紧）。
 */
@Entity
@Table(name = "egress_rules")
public class EgressRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** host glob，入库前经 EgressHostMatcher.normalizePattern 小写规范化 */
    @Column(name = "host_pattern", nullable = false, length = 255)
    private String hostPattern;

    /** 出口节点（弱关联 agent_nodes.id；节点被删后规则保留但路由失败可见） */
    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    /** 默认 true（实体初始值 + getter 兜底，不加 @ColumnDefault——MySQL bit 列事故先例） */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 匹配顺序（先命中先生效，升序） */
    @Column(name = "sort", nullable = false)
    private int sort;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getHostPattern() { return hostPattern; }
    public void setHostPattern(String hostPattern) { this.hostPattern = hostPattern; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public boolean isEnabled() { return enabled == null || enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public int getSort() { return sort; }
    public void setSort(int sort) { this.sort = sort; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
