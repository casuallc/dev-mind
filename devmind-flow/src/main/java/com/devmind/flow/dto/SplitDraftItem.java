package com.devmind.flow.dto;

import java.util.List;

/**
 * 拆分草稿项（CAP-14）：AI 拆分的工作单元草稿，人编辑后随 confirm-split 提交固化。
 *
 * @param type      DESIGN / DEVELOPMENT / TEST / DOCUMENT / REVIEW
 * @param title     标题
 * @param spec      执行说明（起会话时作为 taskSpec 注入）
 * @param dependsOn 依赖的本清单内其他项下标（0 起）
 */
public record SplitDraftItem(String type, String title, String spec, List<Integer> dependsOn) {
}
