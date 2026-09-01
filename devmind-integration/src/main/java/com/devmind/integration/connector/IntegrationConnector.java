package com.devmind.integration.connector;

import com.devmind.integration.model.IntegrationEntity;

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

    record TestResult(boolean ok, String message, String detail) {}

    record ExternalProject(String key, String name, String url, String defaultBranch) {}

    record MrSpec(String projectKey, String sourceBranch, String targetBranch,
                  String title, String description) {}

    record MergeRequestRef(String iid, String url, String state, boolean reused) {}

    record ReleaseSpec(String projectKey, String tagName, String name, String description) {}

    record ReleaseRef(String tagName, String url, boolean reused) {}
}
