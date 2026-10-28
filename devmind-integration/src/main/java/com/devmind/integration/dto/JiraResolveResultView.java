package com.devmind.integration.dto;

/**
 * CAP-101：验收联动解决结果。transitioned=false = 动态清单无解决类转换（issue 可能已在终态），
 * 不算失败；worklogError 非空 = 转换已成功但工时登记失败（部分成功，不抛错由前端提示）。
 */
public record JiraResolveResultView(boolean transitioned, String transitionName, String remoteStatus,
                                    Long loggedSeconds, String worklogError) {
}
