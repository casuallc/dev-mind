package com.devmind.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 需求创建/更新请求。不含 source/externalKey——来源由创建路径决定（API=LOCAL，同步=JIRA），防客户端伪造。
 * JIRA 来源需求的托管字段（title/description/type/priority/assignee/reporter/labels/fixVersions/dueDate）
 * 在服务端 update 时被静默忽略（本地只读，由同步维护）。
 */
public record RequirementRequest(
        @NotBlank String title,
        String description,
        String ownerId,
        Long docId,
        /** 需求类型：FEATURE/BUG/IMPROVEMENT/TASK，空则 FEATURE（创建）或不变（更新） */
        String type,
        /** 优先级（Jira 词表 Highest/High/Medium/Low/Lowest，存字符串保持开放） */
        String priority,
        String assignee,
        String reporter,
        List<String> labels,
        List<String> fixVersions,
        /** 截止日期 yyyy-MM-dd，非法值 BAD_REQUEST */
        String dueDate) {
}
