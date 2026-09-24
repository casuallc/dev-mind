package com.devmind.bookmark.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * CAP-64 FR-07 分享边：owner_id = 分享者，target_user = 接收者；bookmark_id / group_id 恰好一个非空
 * （服务层校验，不依赖 DB CHECK）。语义为只读引用，源数据变更实时反映，撤销即不可见。
 *
 * <p>唯一键按 CAP-64 §5 声明；注意 SQL 唯一约束对 NULL 视作互不相同，
 * 所以「同一条收藏重复分享给同一人」由服务层判重（见 BookmarkShareService#create），DB 约束只兜底非空列组合。</p>
 */
@Entity
@Table(name = "bookmark_shares",
        uniqueConstraints = @UniqueConstraint(name = "uk_bookmark_shares",
                columnNames = {"owner_id", "target_user", "bookmark_id", "group_id"}),
        indexes = @Index(name = "idx_bookmark_shares_target", columnList = "target_user"))
public class BookmarkShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 分享者（users.username） */
    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "bookmark_id")
    private Long bookmarkId;

    @Column(name = "group_id")
    private Long groupId;

    /** 接收者（users.username） */
    @Column(name = "target_user", nullable = false, length = 64)
    private String targetUser;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public Long getBookmarkId() { return bookmarkId; }
    public void setBookmarkId(Long bookmarkId) { this.bookmarkId = bookmarkId; }

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }

    public String getTargetUser() { return targetUser; }
    public void setTargetUser(String targetUser) { this.targetUser = targetUser; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
