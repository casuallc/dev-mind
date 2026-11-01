package com.devmind.test.dto;

/** 从失败用例创建工作单元/缺陷：requirementId 缺省取运行关联需求。 */
public record TestRunWorkItemRequest(
        Long resultId,
        String requirementId,
        String type,
        String title,
        String spec) {
}
