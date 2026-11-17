package com.devmind.chat.dto;

import java.util.List;

/**
 * REQ-23：问答列表分页响应。
 * items = 当前用户活跃问答（全部）+ 最近 limit 条终态，按 createdAt desc，id 不重复；
 * hasMore = 终态部分还有更早数据。
 */
public record ChatPageView(List<ChatView> items, boolean hasMore) {
}
