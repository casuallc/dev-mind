package com.devmind.worklog.service;

/**
 * CAP-41 FR-03/04 资产常量：内置场景 code 与 skill 名。
 * 种子（{@link WorklogAssetSeeder}）、生成链路（{@link ReportService}）、
 * 成稿镜像（{@link WorklogOutputMirror}）三方共用，避免散落字符串漂移。
 */
public final class WorklogScenarios {

    /** GLOBAL skill 名（kebab-case，落 .claude/skills/worklog/） */
    public static final String SKILL_NAME = "worklog";

    /** 日报生成场景 code */
    public static final String DAILY = "worklog-daily";

    /** 周报生成场景 code */
    public static final String WEEKLY = "worklog-weekly";

    private WorklogScenarios() {
    }
}
