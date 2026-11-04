package com.devmind.common.project;

/**
 * CAP-62 项目成员数据权限 SPI（草约，CAP-62 未实施——本接口先行立约，
 * 消费方以 {@code ObjectProvider<ProjectAccessChecker>} 探测注入：<b>缺席 = 不过滤</b>，
 * CAP-62 落地后自动收紧）。首个消费方 = CAP-66 安全扫描。
 */
public interface ProjectAccessChecker {

    /** 项目可读（列表/详情/导出等查询面） */
    boolean canRead(String username, String projectId);

    /** 项目可写（发起任务/抑制/复核等操作面） */
    boolean canWrite(String username, String projectId);
}
