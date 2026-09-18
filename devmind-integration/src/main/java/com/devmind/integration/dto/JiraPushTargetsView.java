package com.devmind.integration.dto;

import java.util.List;
import java.util.Map;

/**
 * CAP-47 FR-02 推送弹窗一次给齐的候选与默认值（打开弹窗只发这一个请求）。
 *
 * <p>与 {@link JiraPushOptionsView} 的差别：本接口**不抛错**——实例/默认目标上的选项拉取失败时
 * 降级为空表并置 {@code optionsError}，保证弹窗一定打得开（用户可换实例再试）；
 * {@code optionsError} 为 null 表示选项拉取正常（空表即「该项目下当前账号确实没有可创建的类型」）。
 *
 * <p>{@code identitySource}：PERSONAL=将用当前用户个人账号推送；BOT=用实例机器人凭证；
 * NONE=两者都没有（推送必 400，弹窗须引导去「我的 → 第三方账号」绑定并禁用提交）。
 *
 * <p>回链文案 = 需求编号（REQ-N）+ " · " + 详情页 URL：URL 部分由前端按 {@code window.location.origin} 拼
 * （平台无自身 base-url 配置），编号与格式由服务端拼（见 {@code JiraPushService.composeDescription}）。
 *
 * @param syncCovered 存在同实例 + 同项目 key 且 enabled 的 jira_sync_configs → 托管字段会自动刷新
 */
public record JiraPushTargetsView(List<Instance> instances,
                                  Long defaultIntegrationId,
                                  String defaultJiraProjectKey,
                                  List<JiraOptionView> jiraProjects,
                                  List<JiraOptionView> issueTypes,
                                  List<JiraOptionView> priorities,
                                  Defaults defaults,
                                  String identitySource,
                                  boolean syncCovered,
                                  String optionsError) {

    /** 候选集成实例（TYPE_JIRA + ENABLED）；baseUrl 仅展示用 */
    public record Instance(Long id, String name, String baseUrl) {
    }

    /**
     * 各字段默认值（取需求当前值 + 项目级推送默认值，弹窗内可改）；dueDate 为 yyyy-MM-dd 字符串。
     *
     * <p>**只回填与 Jira 同域的字段**：标题/描述/标签/截止日期直接取；priority 须命中实例词表
     * （见 {@code JiraPushService.prefillPriority}）；**不回填 assignee**——平台 assignee 是人名
     * （「刘长青」），Jira {@code assignee.name} 要的是登录名，回填要么 400
     * 「用户 '刘长青' 不存在」，要么在人名恰好与某登录名相同时静默指派给错误的人。
     *
     * <p>新增的 {@code issueTypeId}/{@code assigneeName}/{@code extraFields} 来自项目级推送默认值配置
     * （{@code JiraPushDefaultsEntity}）——需求本体没有这些字段的概念，只能来自项目默认。
     * 默认 Jira 项目 key 不在此处：顶层 {@code defaultJiraProjectKey} 已承载（项目推送默认值优先，
     * 未配时退同步配置），避免两个字段各说各话。
     */
    public record Defaults(String title, String description, String priority, List<String> labels,
                           String dueDate,
                           String issueTypeId, String assigneeName,
                           Map<String, Object> extraFields) {
    }
}
