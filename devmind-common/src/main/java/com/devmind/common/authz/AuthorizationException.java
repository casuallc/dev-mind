package com.devmind.common.authz;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;

/**
 * CAP-108 授权失败异常：对外语义映射（§7.3）——
 * 未认证 401；可见但不可执行 403；不存在或不可见统一 404；策略/服务不可用 503。
 */
public class AuthorizationException extends DevMindException {

    private final String reasonCode;

    public AuthorizationException(ErrorCode errorCode, String reasonCode, String message) {
        super(errorCode, message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    /** 由决策构造：RESOURCE_HIDDEN → 404；ERROR → 503；SUBJECT_UNKNOWN/INACTIVE → 401；其余 DENY → 403。 */
    public static AuthorizationException from(AuthorizationDecision decision, String operationId) {
        String reason = decision.reasonCode() == null ? "UNKNOWN" : decision.reasonCode();
        String detail = decision.message() == null ? "" : "（" + decision.message() + "）";
        return switch (decision.outcome()) {
            case ERROR -> new AuthorizationException(ErrorCode.SERVICE_UNAVAILABLE, reason,
                    "操作暂不可用: " + operationId + detail);
            case DENY -> switch (reason) {
                case ReasonCodes.RESOURCE_HIDDEN -> new AuthorizationException(
                        ErrorCode.NOT_FOUND, reason, "资源不存在");
                case ReasonCodes.SUBJECT_UNKNOWN, ReasonCodes.SUBJECT_INACTIVE ->
                        new AuthorizationException(ErrorCode.UNAUTHORIZED, reason,
                                "未认证或账号不可用" + detail);
                default -> new AuthorizationException(ErrorCode.FORBIDDEN, reason,
                        "无权限执行: " + operationId + detail);
            };
            case ALLOW -> throw new IllegalArgumentException("ALLOW 决策不产生异常");
        };
    }
}
