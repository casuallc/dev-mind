package com.devmind.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * CAP-61 菜单目录（menu_items）：由代码目录（MenuCatalog）启动同步落库。
 * CAP-103 起一个节点可以是菜单（MENU）、页签（TAB）或按钮操作（ACTION）。
 * retired=true 表示节点已下架，保留行让历史授权可读，物理不删。
 */
@Entity
@Table(name = "menu_items")
public class MenuItemEntity {

    public static final String ZONE_TOP = "TOP";
    public static final String ZONE_PROJECT = "PROJECT";
    public static final String ZONE_ADMIN = "ADMIN";

    public static final String TYPE_MENU = "MENU";
    public static final String TYPE_TAB = "TAB";
    public static final String TYPE_ACTION = "ACTION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外稳定标识（授权/种子都用它），path 变不动 key */
    @Column(name = "menu_key", nullable = false, unique = true, length = 64)
    private String menuKey;

    @Column(nullable = false, length = 64)
    private String title;

    @Column(nullable = false, length = 128)
    private String path;

    /** TOP / PROJECT / ADMIN */
    @Column(nullable = false, length = 16)
    private String zone;

    /** ADMIN 区的分组名；其余区为空 */
    @Column(name = "group_name", length = 64)
    private String groupName;

    /** 前端图标注册表的 key */
    @Column(length = 64)
    private String icon;

    @Column(name = "sort_no", nullable = false)
    private int sortNo;

    /** CAP-103：MENU / TAB / ACTION；历史行空值按 MENU 兼容 */
    @Column(name = "node_type", length = 16)
    private String nodeType;

    /** 页签/按钮的上级节点编码；菜单为空 */
    @Column(name = "parent_key", length = 64)
    private String parentKey;

    /** 按钮是否写数据：前端对只读角色隐藏，后端规则拦截 */
    @Column(name = "write_action")
    private Boolean writeAction;

    @Column(nullable = false)
    private boolean retired;

    @Column(name = "created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMenuKey() { return menuKey; }
    public void setMenuKey(String menuKey) { this.menuKey = menuKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public int getSortNo() { return sortNo; }
    public void setSortNo(int sortNo) { this.sortNo = sortNo; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getParentKey() { return parentKey; }
    public void setParentKey(String parentKey) { this.parentKey = parentKey; }
    public boolean isWriteAction() { return Boolean.TRUE.equals(writeAction); }
    public void setWriteAction(Boolean writeAction) { this.writeAction = writeAction; }
    public boolean isRetired() { return retired; }
    public void setRetired(boolean retired) { this.retired = retired; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
