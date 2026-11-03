package com.devmind.common.authz;

/**
 * CAP-108 执行来源。source 只能增加约束（如 AI 来源写命令强制确认凭据），不能提升角色或绕过 ACTION。
 */
public enum ExecutionSource {
    WEB,
    COPILOT_WEB,
    BOT,
    OPEN_API,
    ASYNC,
    SYSTEM
}
