package com.devmind.project.dto;

/**
 * 工作单元摘要（跨模块回链展示用）：执行器（构建/部署/测试/发版）列表按 workItemId 批量补全，
 * 使记录可回链到所属需求。code 为项目内编号（WI-&lt;seq&gt; / REQ-&lt;seq&gt;）。
 */
public record WorkItemBrief(
        String id,
        String code,
        String title,
        String requirementId,
        String requirementCode,
        String requirementTitle) {
}
