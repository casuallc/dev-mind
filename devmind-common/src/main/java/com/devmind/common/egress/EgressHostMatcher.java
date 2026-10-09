package com.devmind.common.egress;

import java.util.Locale;

/**
 * CAP-70 FR-03：egress_rules 的 host glob 匹配。支持两种形态（大小写不敏感、不区分端口）：
 * <ul>
 *   <li>精确主机名：{@code gitlab.corp.com}；</li>
 *   <li>后缀通配：{@code *.corp.com}——匹配 {@code corp.com} 的任意子域（含多级），
 *   也匹配 {@code corp.com} 本身（运维直觉：配 *.corp.com 就是「整个域都走」）。</li>
 * </ul>
 * 服务端（规则命中选出口）与 runner（allowedHosts 快照二次校验）共用本类，保证两侧语义一致。
 */
public final class EgressHostMatcher {

    private EgressHostMatcher() {
    }

    /** 规范化 host：去端口、去尾部点、小写；非法/空输入返回空串（永不匹配） */
    public static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        // 剥 IPv6 字面量方括号
        if (h.startsWith("[") && h.contains("]")) {
            return h.substring(1, h.indexOf(']'));
        }
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    /** 规范化规则 pattern（入库前调用）：小写、去空白/端口/尾部点；非法返回空串 */
    public static String normalizePattern(String pattern) {
        if (pattern == null) {
            return "";
        }
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        if (p.startsWith("*.")) {
            p = "*." + normalizeHost(p.substring(2));
            return p.length() > 2 && !p.substring(2).isEmpty() ? p : "";
        }
        return normalizeHost(p);
    }

    /** pattern（已规范化或原始均可）是否命中 host（带不带端口均可） */
    public static boolean matches(String pattern, String host) {
        String p = normalizePattern(pattern);
        String h = normalizeHost(host);
        if (p.isEmpty() || h.isEmpty()) {
            return false;
        }
        if (p.startsWith("*.")) {
            String suffix = p.substring(2);
            return h.equals(suffix) || h.endsWith("." + suffix);
        }
        return h.equals(p);
    }
}
