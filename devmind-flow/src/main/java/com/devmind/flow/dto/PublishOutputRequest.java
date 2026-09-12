package com.devmind.flow.dto;

/**
 * CAP-39 FR-03 手动推送产出为需求文档请求。
 *
 * @param fileName      session_outputs 中的产出文件名（如 analysis.md）
 * @param kind          文档类型：analysis / design / requirement
 * @param requirementId 目标需求
 * @param mode          create = 新建文档（design 同步落 Design(DRAFT)）；update = 目标文档存新版本
 * @param docId         update 模式必填：目标文档 id（须与 requirementId/kind 一致）
 * @param title         create 模式可选：文档标题（默认「类型名 - 需求标题」）
 * @param changeNote    update 模式可选：版本说明（默认「手动推送自会话 xxx」）
 */
public record PublishOutputRequest(String fileName, String kind, String requirementId, String mode,
                                   Long docId, String title, String changeNote) {
}
