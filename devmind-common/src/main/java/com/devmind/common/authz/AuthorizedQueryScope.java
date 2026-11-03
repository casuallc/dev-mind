package com.devmind.common.authz;

import java.util.Set;

/**
 * CAP-108 §4.2.3 查询范围 SPI：列表/搜索/统计在分页与聚合之前应用同一资源策略生成的数据范围。
 * 实现方 devmind-project（持有可见性与成员表）。
 */
public interface AuthorizedQueryScope {

    /**
     * 当前主体可读的项目 ID 集合。空集 = 无任何可读项目（调用方应得到空列表，不是全量）。
     * ADMIN/SYSTEM 语义由实现决定（通常 null 元素不可用，实现可返回全部项目 ID）。
     */
    Set<String> readableProjectIds(ResolvedSubject subject);
}
