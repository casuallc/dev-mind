package com.devmind.session.dto;

import java.util.List;

/**
 * REQ-23：会话列表分页响应。
 * items = 活跃会话（scope 内全部）+ 最近 limit 条终态，按 createdAt desc，id 不重复；
 * hasMore = 终态部分还有更早数据。
 */
public record SessionPageView(List<SessionView> items, boolean hasMore) {
}
