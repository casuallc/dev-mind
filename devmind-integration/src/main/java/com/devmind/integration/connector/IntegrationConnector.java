package com.devmind.integration.connector;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.integration.model.IntegrationEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
     * 执行 issue 工作流转换（CAP-19 FR-08）。**写操作**——本 SPI 唯一放行写请求的路径，
     * 仅限 transitions 端点；transitionId 必须来自当前 {@link #listTransitions} 结果。
     */
    default void transitionIssue(IntegrationEntity cfg, String token, String issueKey, String transitionId) {
        throw new DevMindException(ErrorCode.BAD_REQUEST, type() + " 不支持 issue 状态转换");
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

    /** 通用 issue 视图（命名对齐 Jira 字段；后续其他 issue 平台复用时映射到同一结构） */
    record JiraIssue(String key, String summary, String description, String issueType,
                     String priority, List<String> labels, String status,
                     Instant created, Instant updated, String reporter,
                     String assignee, LocalDate dueDate, List<String> fixVersions) {}

    /** issue 工作流转换（CAP-19 FR-08）：id=转换 id（执行时回传），name=转换名，toStatus=目标状态名 */
    record IssueTransition(String id, String name, String toStatus) {}
}
