package com.devmind.common.authz;

/**
 * CAP-108 §7.3 授权决策：ALLOW / DENY / ERROR 三分；ERROR 表示授权服务或策略不可用，停止且不得当作允许。
 */
public record AuthorizationDecision(Outcome outcome, String reasonCode, String message) {

    public enum Outcome { ALLOW, DENY, ERROR }

    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(Outcome.ALLOW, ReasonCodes.ALLOWED, null);
    }

    public static AuthorizationDecision deny(String reasonCode, String message) {
        return new AuthorizationDecision(Outcome.DENY, reasonCode, message);
    }

    public static AuthorizationDecision error(String reasonCode, String message) {
        return new AuthorizationDecision(Outcome.ERROR, reasonCode, message);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }
}
