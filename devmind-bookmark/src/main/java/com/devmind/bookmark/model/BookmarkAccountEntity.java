package com.devmind.bookmark.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * CAP-64 FR-05 收藏关联账号。随收藏级联删除；password_enc 为 SecretCipher 密文串（非 @Lob）。
 */
@Entity
@Table(name = "bookmark_accounts",
        indexes = @Index(name = "idx_bookmark_accounts_bookmark", columnList = "bookmark_id"))
public class BookmarkAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bookmark_id", nullable = false)
    private Long bookmarkId;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(length = 256)
    private String username;

    /** SecretCipher 密文（域分隔串 bookmark-account）；空 = 无密码 */
    @Column(name = "password_enc", length = 1024)
    private String passwordEnc;

    @Column(length = 512)
    private String note;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBookmarkId() { return bookmarkId; }
    public void setBookmarkId(Long bookmarkId) { this.bookmarkId = bookmarkId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordEnc() { return passwordEnc; }
    public void setPasswordEnc(String passwordEnc) { this.passwordEnc = passwordEnc; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
