package com.devmind.common.integration;

/**
 * 发版平台集成钩子（CAP-18 FR-06）：发版成功后把 tag 推到绑定远程并创建平台 Release。
 * 接口定义在 common（同 ServerCredentialCipher 的插件化先例），CAP-11 经 ObjectProvider 探测——
 * 有实现（devmind-integration 装配时）则调用，无实现/项目未绑定 Integration 时静默跳过。
 * 实现方内部自审，失败只影响返回值文本，不打断发版主流程。
 */
public interface PlatformIntegrationHook {

    /**
     * 发版成功回调：push tag + 创建平台 Release（External Link 登记在实现方完成）。
     *
     * @param projectId 项目
     * @param releaseId 发版单 id
     * @param tagName   已打好的本地 tag（v&lt;version&gt;）
     * @param version   版本号
     * @param summary   发版摘要（作平台 Release 描述）
     * @return 一行结果摘要（写进发版日志）；null 表示无绑定/未启用，已静默跳过
     */
    String onReleaseSuccess(String projectId, Long releaseId, String tagName, String version, String summary);
}
