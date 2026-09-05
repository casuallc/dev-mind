package com.devmind.worklog.dto;

import jakarta.validation.constraints.NotNull;

/** 订阅勾选请求（Jackson 3：布尔字段必用包装类型）。 */
public record SubscriptionRequest(@NotNull Boolean subscribed) {}
