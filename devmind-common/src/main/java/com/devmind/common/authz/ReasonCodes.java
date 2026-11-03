package com.devmind.common.authz;

/**
 * CAP-108 §7.3 内部拒绝/错误原因码（审计与定位用，不默认向用户暴露授权明细）。
 */
public final class ReasonCodes {

    public static final String ALLOWED = "ALLOWED";
    public static final String SUBJECT_UNKNOWN = "SUBJECT_UNKNOWN";
    public static final String SUBJECT_INACTIVE = "SUBJECT_INACTIVE";
    public static final String SUBJECT_TYPE_DENIED = "SUBJECT_TYPE_DENIED";
    public static final String OPERATION_UNKNOWN = "OPERATION_UNKNOWN";
    public static final String ROLE_DENIED = "ROLE_DENIED";
    public static final String ACTION_DENIED = "ACTION_DENIED";
    public static final String RESOURCE_HIDDEN = "RESOURCE_HIDDEN";
    public static final String OWNER_DENIED = "OWNER_DENIED";
    public static final String SCOPE_DENIED = "SCOPE_DENIED";
    public static final String POLICY_UNAVAILABLE = "POLICY_UNAVAILABLE";
    public static final String CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED";
    public static final String TARGET_CHANGED = "TARGET_CHANGED";

    private ReasonCodes() {
    }
}
