package com.devmind.bookmark.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * CAP-64 FR-01 收藏条目。归属 owner_id（users.username）；group_id 可空 = 未分组。
 * last_* 四列为 FR-04 探测结果与 FR-06 最近访问的落库点。
 */
@Entity
@Table(name = "bookmarks",
        indexes = @Index(name = "idx_bookmarks_owner_group", columnList = "owner_id,group_id"))
public class BookmarkEntity {

    /** FR-04 探测状态：从未探测 */
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    /** FR-04 探测状态：2xx/3xx */
    public static final String STATUS_OK = "OK";
    /** FR-04 探测状态：非 2xx/3xx 或连接异常 */
    public static final String STATUS_FAIL = "FAIL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（users.username） */
    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    /** 所属分组；null = 未分组 */
    @Column(name = "group_id")
    private Long groupId;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 1024)
    private String description;

    @Column(name = "favicon_url", length = 2048)
    private String faviconUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 字符串默认值必带内层引号，裸常量 H2 建表失败（CLAUDE.md 红线） */
    @Column(name = "last_status", nullable = false, length = 16)
    @ColumnDefault("'UNKNOWN'")
    private String lastStatus = STATUS_UNKNOWN;

    /** 成功时为 HTTP 状态码；失败时为异常同义描述（TIMEOUT/DNS/CONNECT/BAD_URL/PRIVATE/REDIRECT） */
    @Column(name = "last_status_code", length = 8)
    private String lastStatusCode;

    @Column(name = "last_latency_ms")
    private Long lastLatencyMs;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_visited_at")
    private Instant lastVisitedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getFaviconUrl() { return faviconUrl; }
    public void setFaviconUrl(String faviconUrl) { this.faviconUrl = faviconUrl; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public String getLastStatusCode() { return lastStatusCode; }
    public void setLastStatusCode(String lastStatusCode) { this.lastStatusCode = lastStatusCode; }

    public Long getLastLatencyMs() { return lastLatencyMs; }
    public void setLastLatencyMs(Long lastLatencyMs) { this.lastLatencyMs = lastLatencyMs; }

    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }

    public Instant getLastVisitedAt() { return lastVisitedAt; }
    public void setLastVisitedAt(Instant lastVisitedAt) { this.lastVisitedAt = lastVisitedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
