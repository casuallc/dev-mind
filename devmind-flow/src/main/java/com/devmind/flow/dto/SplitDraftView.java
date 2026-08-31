package com.devmind.flow.dto;

import java.util.List;

/**
 * 拆分草稿视图（CAP-14 FR-06）：解析自最近一次拆分会话的 wi-plan.json。
 *
 * @param sessionId 产出草稿的拆分会话 id（无可用草稿时为 null）
 * @param items     草稿项（解析失败/未产出时为空列表，前端退回手工建 WI）
 */
public record SplitDraftView(String sessionId, List<SplitDraftItem> items) {
}
