package com.devmind.integration.connector;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.model.IntegrationEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * CAP-18 平台连接器 SPI：每种平台一个实现，按 {@link #type()} 注册。
 * token 由服务层经 IntegrationCipher 解密后显式传入——连接器不接触密文与持久层，
 * 实现内不得把 token 写进日志/异常消息。
 */
public interface IntegrationConnector {

    /** GITLAB / GITHUB / JIRA（与 IntegrationEntity.TYPE_* 对应） */
    String type();

    /** FR-02 连接测试：验证 base_url 可达 + token 有效，返回诊断信息 */
    TestResult testConnection(IntegrationEntity cfg, String token);

    /** FR-03 绑定辅助：列出 token 可见的平台项目 */
    List<ExternalProject> listProjects(IntegrationEntity cfg, String token);

    /** FR-05 创建 MR/PR；已存在未关闭的同源 MR 时应返回既有（reused=true），不报错 */
    MergeRequestRef createMergeRequest(IntegrationEntity cfg, String token, MrSpec spec);

    /** FR-06 创建平台 Release；tag 对应 Release 已存在时返回既有（reused=true） */
    ReleaseRef createRelease(IntegrationEntity cfg, String token, ReleaseSpec spec);

    /**
     * issue 拉取（issue 跟踪型平台如 Jira；git 平台默认不支持）。
     * 只读操作——连接器不得向平台发起任何写请求。
     */
    default IssuePage searchIssues(IntegrationEntity cfg, String token, IssueQuery query) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持 issue 拉取");
    }

    /**
     * 列出 issue 当前可用工作流转换（CAP-19 FR-08；issue 跟踪型平台如 Jira）。
     * 只读操作——转换清单随 issue 状态与工作流配置动态变化，不得硬编码。
     */
    default List<IssueTransition> listTransitions(IntegrationEntity cfg, String token, String issueKey) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持 issue 状态转换");
    }

    /**
     * 执行 issue 工作流转换（CAP-19 FR-08）。**写操作**——与 {@link #logWork}、{@link #createIssue}
     * 同属本 SPI 放行写请求的三条路径；transitionId 必须来自当前 {@link #listTransitions} 结果。
     */
    default void transitionIssue(IntegrationEntity cfg, String token, String issueKey, String transitionId) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持 issue 状态转换");
    }

    /**
     * 创建 issue（CAP-47 FR-01；issue 跟踪型平台如 Jira）。**写操作**——与 transitions/worklog
     * 同属回写通道，仅在用户显式触发时调用（不做自动推送）。
     */
    default IssueRef createIssue(IntegrationEntity cfg, String token, IssueSpec spec) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持创建 issue");
    }

    /**
     * 单条读取 issue（CAP-47 FR-03；issue 跟踪型平台如 Jira）。只读操作。
     * 与 {@link #searchIssues} 的区别：**不经过搜索索引**——新建 issue 后索引有延迟，
     * /search 可能返回 0 条，回读必须走本方法。
     */
    default JiraIssue getIssue(IntegrationEntity cfg, String token, String issueKey, String fields) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持读取 issue");
    }

    /**
     * 列出某项目下**当前账号可创建**的 issue 类型（CAP-47 FR-02）。
     * 只读操作——类型清单随实例语言包与项目配置变化，不得硬编码；仅返回顶层类型（子任务被过滤）。
     */
    default List<IssueTypeRef> listIssueTypes(IntegrationEntity cfg, String token, String projectKey) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持读取任务类型");
    }

    /** 列出实例的优先级词表（CAP-47 FR-02）。只读操作；实例关闭优先级功能时返回空表。 */
    default List<PriorityRef> listPriorities(IntegrationEntity cfg, String token) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持读取优先级");
    }

    /**
     * 列出「在某项目下创建某 issue 类型」时的字段元数据（CAP-47 FR-08）。
     * 只读操作——这是平台能回答「Jira 会因哪些字段拒我」的**唯一权威来源**：
     * issue 类型可以配一堆必填字段（模块/影响版本/修复版本/时间跟踪/自定义字段），
     * 硬编码字段清单必然落后于实例配置。返回含全部字段（不筛必填），由调用方按 required 分区。
     */
    default List<CreateFieldRef> listCreateFields(IntegrationEntity cfg, String token,
                                                  String projectKey, String issueTypeId) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持读取创建字段元数据");
    }

    /** 列出项目下可指派用户（CAP-47 FR-02，q 为用户名/显示名关键字，空则取默认列表）。只读操作。 */
    default List<UserRef> listAssignableUsers(IntegrationEntity cfg, String token, String projectKey, String q) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持读取可指派用户");
    }

    /**
     * 拉取 issue 附件内容（CAP-19 FR-09；issue 跟踪型平台如 Jira）。
     * 只读操作——filename 为描述 wiki 标记（!name.png!）引用的附件文件名，按名精确匹配。
     */
    default IssueAttachment fetchIssueAttachment(IntegrationEntity cfg, String token, String issueKey,
                                                 String filename) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持 issue 附件拉取");
    }

    /**
     * 登记工时（CAP-27；issue 跟踪型平台如 Jira）。**写操作**——与 transitions 同属回写通道，
     * comment 可空。seconds 为本次登记的工时秒数（Jira worklog timeSpentSeconds）。
     */
    default void logWork(IntegrationEntity cfg, String token, String issueKey, long seconds, String comment) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持工时登记");
    }

    record TestResult(boolean ok, String message, String detail) {}

    record ExternalProject(String key, String name, String url, String defaultBranch) {}

    record MrSpec(String projectKey, String sourceBranch, String targetBranch,
                  String title, String description) {}

    record MergeRequestRef(String iid, String url, String state, boolean reused) {}

    record ReleaseSpec(String projectKey, String tagName, String name, String description) {}

    record ReleaseRef(String tagName, String url, boolean reused) {}

    /** issue 查询：jql 为完整查询语句，startAt/maxResults 分页，fields 逗号分隔的字段清单 */
    record IssueQuery(String jql, int startAt, int maxResults, String fields) {}

    /** issue 分页结果（对齐 Jira /search 响应结构） */
    record IssuePage(int startAt, int maxResults, int total, List<JiraIssue> issues) {}

    /** 通用 issue 视图（命名对齐 Jira 字段；后续其他 issue 平台复用时映射到同一结构）；
     *  originalEstimateSec/timeSpentSec 为 time tracking 秒数（CAP-27，实例未启用工时跟踪时为 null） */
    record JiraIssue(String key, String summary, String description, String issueType,
                     String priority, List<String> labels, String status,
                     Instant created, Instant updated, String reporter,
                     String assignee, LocalDate dueDate, List<String> fixVersions,
                     Long originalEstimateSec, Long timeSpentSec) {}

    /** issue 工作流转换（CAP-19 FR-08）：id=转换 id（执行时回传），name=转换名，toStatus=目标状态名 */
    record IssueTransition(String id, String name, String toStatus) {}

    /**
     * issue 创建入参（CAP-47 FR-01）。除 projectKey/issueTypeId/summary 外均可空——
     * **空值字段连接器一律不写进平台 payload**（写 null 会显式清空平台侧默认值）。
     * assigneeName 为平台用户名（Jira Server 的 name，非 displayName）。
     *
     * <p>{@code extraFields} 为 CAP-47 FR-08 的动态字段：键是平台字段 id（{@code components}/
     * {@code fixVersions}/{@code customfield_10207}…），值是**已按该字段类型组装好的平台取值**
     * （如 {@code [{"id":"10000"}]} / {@code {"originalEstimate":"2h"}}），连接器原样写进 payload。
     * 平台语义的组装由服务层按 createmeta 的字段类型完成——连接器不猜字段类型。
     */
    record IssueSpec(String projectKey, String issueTypeId, String summary, String description,
                     String priorityName, String assigneeName, List<String> labels, LocalDate dueDate,
                     Map<String, Object> extraFields) {}

    /** issue 创建结果（CAP-47）：id 为平台内部 id，key 为外部键（如 PROJ-123），url 为可点击地址 */
    record IssueRef(String id, String key, String url) {}

    /** issue 类型（CAP-47 FR-02）：name 受实例语言包影响（中文实例为「任务/缺陷」），原样展示不映射 */
    record IssueTypeRef(String id, String name, boolean subtask) {}

    /** 优先级词表项（CAP-47 FR-02）：name 为实例词表原文 */
    record PriorityRef(String id, String name) {}

    /**
     * 创建字段元数据（CAP-47 FR-08）。{@code type}/{@code items} 为平台 schema 原文
     * （Jira 的 {@code array}/{@code option}/{@code date}/{@code timetracking}…；type=array 时
     * items 是元素类型），原样透出不做映射——能否渲染成输入项由服务层判定。
     * {@code allowedValues} 为该字段当前合法取值（枚举类字段才有，组件/影响版本/修复版本/
     * 下拉自定义字段都由此拿到候选）；{@code hasDefault} 为真时平台会自填，不必要求用户填。
     */
    record CreateFieldRef(String id, String name, boolean required, String type, String items,
                          List<FieldOption> allowedValues, boolean hasDefault) {}

    /** 创建字段的合法取值项（CAP-47 FR-08）：id 为回传值，value 为展示名 */
    record FieldOption(String id, String value) {}

    /** 可指派用户（CAP-47 FR-02）：name=平台用户名（创建 issue 时回传），displayName=界面展示名 */
    record UserRef(String name, String displayName) {}

    /** issue 附件内容（CAP-19 FR-09）：原始字节 + mime 类型（Jira 附件元数据给出） */
    record IssueAttachment(byte[] content, String mimeType) {}
}
