package com.devmind.flow.dto;

import java.util.List;

/**
 * 拆分清单项（CAP-14/CAP-38）：wi-plan.json 解析载体，CAP-38 起产出直接固化为正式工作单元（无人工编辑环节）。
 *
 * @param type      DESIGN / DEVELOPMENT / TEST / DOCUMENT / REVIEW
 * @param title     标题
 * @param spec      执行说明（起会话时作为 taskSpec 注入）
 * @param dependsOn 依赖的本清单内其他项下标（0 起）
 */
public record SplitDraftItem(String type, String title, String spec, List<Integer> dependsOn) {
}
