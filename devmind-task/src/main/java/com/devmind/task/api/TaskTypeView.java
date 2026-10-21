package com.devmind.task.api;

/**
 * CAP-80 M4 已注册任务类型视图（GET /api/async-tasks/types）——管理页「新建任务」
 * 类型下拉与 payload 预填/执行位置（是否展示节点选择）的元数据来源。
 */
public record TaskTypeView(String type, String description, String payloadTemplate, String executor) {
}
