package com.devmind.decisionlab.dataset.dto;

import java.util.Map;

/**
 * CAP-56 收编结果：这次真收进去几条、剩下的为什么没收，以及收完之后的集详情。
 *
 * <p>带上 {@code dataset} 是因为收编会改变条数与组分布，前端收完必须看到新的现状
 * （而不是自己拿预览的数字去推断）——"收编完还是缺对照组"这件事，要当场看得见。</p>
 *
 * @param added       本次新增条数
 * @param skipped     本次未收编条数（含已收编过的）
 * @param skipReasons 原因码 → 条数
 * @param dataset     收编后的集详情（条数、组分布、冻结提醒都已刷新）
 */
public record RecordsIntakeResult(
        int added,
        int skipped,
        Map<String, Long> skipReasons,
        DatasetDetail dataset) {
}
