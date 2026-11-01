package com.devmind.test.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * CAP-104 test_modules：产品（项目）下的功能模块层，四级目录 = 项目 → 模块 → 套件 → 用例。
 * 存量套件 module_id 为空，前端显示“未分类”，不阻塞升级。
 */
@Entity
@Table(name = "test_modules",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "name"}))
public class TestModuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 32)
    private String projectId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 256)
    private String description;

    /** 逗号分隔标签（POC 用简单串，检索按 LIKE） */
    @Column(length = 256)
    private String tags;

    @Column(length = 64)
    private String owner;

    @Column(name = "sort_no")
    private Integer sortNo;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
