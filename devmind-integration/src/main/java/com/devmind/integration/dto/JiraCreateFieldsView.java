package com.devmind.integration.dto;

import java.util.List;
import java.util.Map;

/**
 * CAP-47 FR-08 选定（实例 + 项目 + 任务类型）后的必填字段清单——平台对「Jira 会因哪些字段拒我」
 * 的权威回答，来自 createmeta。只列**必填且平台没有默认值**的字段：有默认值的平台会自填，
 * 不必拿一堆用不上的输入项淹没用户。
 *
 * <p>{@code requiredFixed} 是固定表单里**已有**输入项、被 Jira 标为必填的字段 id
 * （{@code duedate}/{@code priority}/{@code assignee}/{@code labels}/{@code description}）——
 * 前端给对应控件加必填校验即可，不重复渲染。
 * {@code fields} 需要新渲染；{@code unsupported} 必填但渲染不了（列出并禁用提交）。
 *
 * <p>{@code availableFields} 是**该创建界面上存在的字段 id 全集**（createmeta 未过滤的原始清单）。
 * 前三个列表都只讲「必填」，讲不了「这个界面根本没有标签这个字段」——而 Jira 会因此直接拒掉
 * {@code labels}（{@code HTTP 400 Field 'labels' cannot be set. It is not on the appropriate screen}）。
 * 前端据此隐藏平台固定表单里 Jira 不接受的输入项，提交时也不带上去。
 *
 * <p>{@code prefill} 为字段 id → 预填值：只回填**与 Jira 同域**且**命中实例候选值**的本地值
 * （目前只有 {@code fixVersions}——平台存的版本名本就来自 Jira，命中才回填，见 FR-02 的同域口径）。
 *
 * <p>{@code error} 非空表示元数据拉取失败：此时各列表皆为空、**不禁用提交**——读接口不可用
 * 不该把原本能推的类型也堵死（与 {@link JiraPushTargetsView#optionsError} 同款降级口径）。
 * 前端见 {@code error} 或空 {@code availableFields} 时视作「未知」，固定字段一律照常显示。
 */
public record JiraCreateFieldsView(List<JiraCreateFieldView> fields,
                                   List<String> requiredFixed,
                                   List<JiraCreateFieldView> unsupported,
                                   List<String> availableFields,
                                   Map<String, List<String>> prefill,
                                   String error) {
}
