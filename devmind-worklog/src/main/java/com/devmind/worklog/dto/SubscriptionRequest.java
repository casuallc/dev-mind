package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 订阅勾选请求（Jackson 3：布尔字段必用包装类型）。
 * branches：勾选扫描的分支；null = 不改动（新行缺省跟随默认分支），空列表 = 恢复跟随默认分支。
 */
public record SubscriptionRequest(@NotNull Boolean subscribed, List<String> branches) {}
