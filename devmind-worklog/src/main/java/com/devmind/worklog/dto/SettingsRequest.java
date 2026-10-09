package com.devmind.worklog.dto;

/**
 * 个人设置更新请求（Jackson 3：布尔必用包装类型）。
 * dailyTemplateMd/weeklyTemplateMd：null = 不变；空白串 = 清除自定义（回退内置默认）。
 * remoteUrl/remoteBranch：null = 不变；空白串 = 解绑远端备份。
 *
 * <p>个人执行时间（dailyTime/weeklyTime/gitImportTime，"HH:mm"）：null = 不变；空白串 = 清除
 * 个人设置（跟随全局）。weeklyDay：null = 不变；0 = 清除（跟随全局）；1=周一 … 7=周日。
 * 周报覆盖需 weeklyDay 与 weeklyTime 同时有值，任一清除即整体回落全局。</p>
 */
public record SettingsRequest(Boolean autoDaily, Boolean autoWeekly, Boolean autoGitImport,
                              Integer dailyMinutesTarget,
                              String dailyTemplateMd, String weeklyTemplateMd,
                              String remoteUrl, String remoteBranch,
                              String dailyTime, Integer weeklyDay, String weeklyTime, String gitImportTime) {}
