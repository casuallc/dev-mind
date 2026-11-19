package com.devmind.flow.dto;

import java.util.List;

/**
 * 导入 wi-plan.json 的结果：创建成功的工作单元数量与 id 列表。
 *
 * @param count       创建工作单元条数
 * @param workItemIds 按清单顺序生成的工作单元 id 列表
 */
public record ImportWiPlanResult(int count, List<String> workItemIds) {
}
