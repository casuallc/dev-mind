package com.devmind.common.authz;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * CAP-108 §4.2.2 ACTION 授权表达式：按真实 roleCode 查现有 role_menu_grants。
 * NONE 必须显式使用，仅表示不需额外 ACTION，不跳过身份、资源与 scope 检查。
 */
public record GrantRule(Kind kind, Set<String> keys) {

    public enum Kind { NONE, ANY_OF, ALL_OF }

    public GrantRule {
        keys = keys == null ? Set.of() : Set.copyOf(keys);
        if (kind == Kind.NONE && !keys.isEmpty()) {
            throw new IllegalArgumentException("NONE 不允许携带 ACTION key");
        }
        if (kind != Kind.NONE && keys.isEmpty()) {
            throw new IllegalArgumentException("ANY_OF/ALL_OF 至少一个 ACTION key");
        }
    }

    public static GrantRule none() {
        return new GrantRule(Kind.NONE, Set.of());
    }

    public static GrantRule anyOf(String... keys) {
        return new GrantRule(Kind.ANY_OF, new LinkedHashSet<>(Set.of(keys)));
    }

    public static GrantRule allOf(String... keys) {
        return new GrantRule(Kind.ALL_OF, new LinkedHashSet<>(Set.of(keys)));
    }
}
