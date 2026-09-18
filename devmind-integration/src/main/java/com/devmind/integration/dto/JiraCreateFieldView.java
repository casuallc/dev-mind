package com.devmind.integration.dto;

import java.util.List;

/**
 * CAP-47 FR-08 一个待填写的创建字段。
 *
 * <p>{@code control} 是服务端算出的**渲染控件**——前端只按它 switch，不必理解 Jira schema：
 * {@code MULTI_SELECT}（多选：模块/影响版本/修复版本/多选自定义字段）、{@code SELECT}（下拉）、
 * {@code DATE}、{@code TEXT}、{@code NUMBER}、{@code TIMETRACKING}（初始预估 + 剩余估算两个输入）。
 * 为 {@code null} 表示该字段必填但平台渲染不了（用户选择器/级联选择等），前端须列出来并禁用提交
 * ——这比让用户提交后吃一个 400 诚实。
 *
 * <p>{@code options} 是平台给出的合法取值；枚举类字段必有，自由文本数组（如自定义的标签类字段）
 * 为空，此时前端改渲染成可自由输入的标签框。
 */
public record JiraCreateFieldView(String id, String name, String control, List<JiraOptionView> options) {

    /** 多选：值为 [{id}] 数组（枚举类）或 [文本] 数组（无 options 时） */
    public static final String CONTROL_MULTI_SELECT = "MULTI_SELECT";
    /** 单选下拉：值为 {id}（枚举类） */
    public static final String CONTROL_SELECT = "SELECT";
    /** 日期：值为 'yyyy-MM-dd' */
    public static final String CONTROL_DATE = "DATE";
    /** 文本：值为字符串 */
    public static final String CONTROL_TEXT = "TEXT";
    /** 数字：值为数字 */
    public static final String CONTROL_NUMBER = "NUMBER";
    /** 时间跟踪：值为 {originalEstimate, remainingEstimate}（Jira 时长格式，如 2h / 1d 4h） */
    public static final String CONTROL_TIMETRACKING = "TIMETRACKING";
}
