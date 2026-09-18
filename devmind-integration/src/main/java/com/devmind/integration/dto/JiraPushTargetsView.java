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
 * NONE=两者都没有（推送必 400，弹窗须引导去「个人设置 → 第三方账号」绑定并禁用提交）。
 *
 * <p>{@code templates}：当前用户的全部个人推送模板（FR-10），前端在「实例+项目+类型」
 * 选定/切换时本地匹配并带入字段默认值——模板组合与弹窗选择都可能在打开后变化，
 * 服务端只负责一次给齐，匹配是前端的事。
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
                                  List<TemplateRef> templates,
                                  String identitySource,
                                  boolean syncCovered,
                                  String optionsError) {

    /** 候选集成实例（TYPE_JIRA + ENABLED）；baseUrl 仅展示用 */
    public record Instance(Long id, String name, String baseUrl) {
    }

    /**
     * 各字段默认值（只取需求当前值，弹窗内可改）；dueDate 为 yyyy-MM-dd 字符串。
     *
     * <p>**只回填与 Jira 同域的字段**：标题/描述/标签/截止日期直接取；priority 须命中实例词表
     * （见 {@code JiraPushService.prefillPriority}）；**不回填 assignee**——平台 assignee 是人名
     * （「刘长青」），Jira {@code assignee.name} 要的是登录名，回填要么 400
     * 「用户 '刘长青' 不存在」，要么在人名恰好与某登录名相同时静默指派给错误的人。
     *
     * <p>任务类型/经办人/动态字段的默认值不在此处——需求本体没有这些概念，
     * 它们来自 {@link TemplateRef}（个人推送模板），由前端在组合选定时带入。
     */
    public record Defaults(String title, String description, String priority, List<String> labels,
                           String dueDate) {
    }

    /**
     * FR-10 个人推送模板引用（匹配键 = integrationId + jiraProjectKey + issueTypeId）。
     * 不带 id/userId——匹配与带入只需要这些字段；管理（改/删）走 {@code /api/me/jira-push-templates}。
     */
    public record TemplateRef(Long integrationId,
                              String jiraProjectKey,
                              String issueTypeId,
                              String priorityName,
                              String assigneeName,
                              List<String> labels,
                              Map<String, Object> extraFields) {
    }
}
