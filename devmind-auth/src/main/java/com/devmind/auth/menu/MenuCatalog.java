package com.devmind.auth.menu;

import com.devmind.auth.model.MenuItemEntity;

import java.util.List;

/**
 * CAP-61 FR-01 菜单目录（代码单一数据源）。
 * 新增菜单：在此登记一行 + 前端补路由/图标 key，启动自动落库并按 zone 补默认授权。
 * ADMIN 区的分组（项目与资源/知识库/智能体/智能决策/系统）不是菜单项，只作为 groupName。
 */
public final class MenuCatalog {

    public record Item(String menuKey, String title, String path, String zone,
                       String groupName, String icon, int sortNo) {
    }

    private static final List<Item> ITEMS = List.of(
            // ---- 工作台顶部导航 ----
            new Item("top.home", "工作台", "/home", MenuItemEntity.ZONE_TOP, null, "home", 10),
            new Item("top.projects", "项目", "/overview", MenuItemEntity.ZONE_TOP, null, "folder", 20),
            new Item("top.chats", "AI 问答", "/chats", MenuItemEntity.ZONE_TOP, null, "comment", 30),
            new Item("top.bookmarks", "收藏夹", "/bookmarks", MenuItemEntity.ZONE_TOP, null, "star", 40),
            new Item("top.worklog", "工作日志", "/worklog", MenuItemEntity.ZONE_TOP, null, "field-time", 50),
            new Item("top.settings", "设置", "/settings", MenuItemEntity.ZONE_TOP, null, "setting", 60),
            // ---- 项目二级页签 ----
            new Item("project.overview", "概览", "/overview", MenuItemEntity.ZONE_PROJECT, null, null, 10),
            new Item("project.sessions", "会话", "/sessions", MenuItemEntity.ZONE_PROJECT, null, null, 20),
            new Item("project.requirements", "需求", "/requirements", MenuItemEntity.ZONE_PROJECT, null, null, 30),
            new Item("project.context", "知识", "/context", MenuItemEntity.ZONE_PROJECT, null, null, 40),
            new Item("project.builds", "构建", "/builds", MenuItemEntity.ZONE_PROJECT, null, null, 50),
            new Item("project.deployments", "部署", "/deployments", MenuItemEntity.ZONE_PROJECT, null, null, 60),
            new Item("project.tests", "测试", "/tests", MenuItemEntity.ZONE_PROJECT, null, null, 70),
            new Item("project.releases", "发版", "/releases", MenuItemEntity.ZONE_PROJECT, null, null, 80),
            // ---- 后台侧边（分组见 groupName） ----
            new Item("admin.dashboard", "指挥中心", "/admin/dashboard", MenuItemEntity.ZONE_ADMIN, null, "dashboard", 10),
            new Item("admin.projects", "项目管理", "/admin/projects", MenuItemEntity.ZONE_ADMIN, "项目与资源", "folder", 20),
            new Item("admin.docs", "文档管理", "/admin/docs", MenuItemEntity.ZONE_ADMIN, "项目与资源", "file-text", 30),
            new Item("admin.attachments", "附件管理", "/admin/attachments", MenuItemEntity.ZONE_ADMIN, "项目与资源", "paper-clip", 40),
            new Item("admin.skills", "技能管理", "/admin/skills", MenuItemEntity.ZONE_ADMIN, "项目与资源", "tool", 50),
            new Item("admin.agent-nodes", "Agent 节点", "/admin/agent/nodes", MenuItemEntity.ZONE_ADMIN, "项目与资源", "robot", 60),
            new Item("admin.knowledge", "知识库", "/admin/knowledge", MenuItemEntity.ZONE_ADMIN, "知识库", "read", 70),
            new Item("admin.knowledge-ops", "知识运营", "/admin/knowledge/ops", MenuItemEntity.ZONE_ADMIN, "知识库", "heart", 80),
            new Item("admin.knowledge-reports", "质量报告", "/admin/knowledge/reports", MenuItemEntity.ZONE_ADMIN, "知识库", "bar-chart", 90),
            new Item("admin.bot", "机器人运维", "/admin/bot", MenuItemEntity.ZONE_ADMIN, "智能体", "comment", 100),
            new Item("admin.copilot", "智能体运维", "/admin/copilot", MenuItemEntity.ZONE_ADMIN, "智能体", "interaction", 110),
            new Item("admin.laya-instances", "服务实例", "/admin/laya/instances", MenuItemEntity.ZONE_ADMIN, "智能决策", "partition", 120),
            new Item("admin.laya-records", "决策记录", "/admin/laya/records", MenuItemEntity.ZONE_ADMIN, "智能决策", "fork", 130),
            new Item("admin.laya-lab", "决策实验室", "/admin/laya/lab", MenuItemEntity.ZONE_ADMIN, "智能决策", "experiment", 140),
            new Item("admin.tasks", "任务调度", "/admin/tasks", MenuItemEntity.ZONE_ADMIN, "系统", "schedule", 150),
            new Item("admin.integrations", "平台集成", "/admin/integrations", MenuItemEntity.ZONE_ADMIN, "系统", "api", 160),
            new Item("admin.models", "模型接入", "/admin/models", MenuItemEntity.ZONE_ADMIN, "系统", "thunderbolt", 170),
            new Item("admin.keys", "API 密钥", "/admin/keys", MenuItemEntity.ZONE_ADMIN, "系统", "key", 180),
            new Item("admin.users", "用户管理", "/admin/users", MenuItemEntity.ZONE_ADMIN, "系统", "safety-certificate", 190),
            new Item("admin.notifications", "通知管理", "/admin/notifications", MenuItemEntity.ZONE_ADMIN, "系统", "bell", 200),
            new Item("admin.menus", "菜单权限", "/admin/menus", MenuItemEntity.ZONE_ADMIN, "系统", "menu", 210)
    );

    private MenuCatalog() {
    }

    public static List<Item> items() {
        return ITEMS;
    }
}
