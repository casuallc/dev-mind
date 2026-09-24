package com.devmind.bookmark.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * CAP-64 FR-03 收藏 × 标签多对多关联边（稀疏表，不带审计字段）。删除标签只删本表行，不动收藏。
 */
@Entity
@Table(name = "bookmark_tag_rel",
        uniqueConstraints = @UniqueConstraint(name = "uk_bookmark_tag_rel",
                columnNames = {"bookmark_id", "tag_id"}))
public class BookmarkTagRelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bookmark_id", nullable = false)
    private Long bookmarkId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBookmarkId() { return bookmarkId; }
    public void setBookmarkId(Long bookmarkId) { this.bookmarkId = bookmarkId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }
}
