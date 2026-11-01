package com.devmind.test.spec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** CAP-104 用例签名：type + 请求 + 断言归一化哈希，用于重复提示（不自动合并）。 */
public final class CaseSignatures {

    private CaseSignatures() {
    }

    public static String of(CaseSpec spec) {
        if (spec == null) {
            return hash("empty");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(norm(spec.type())).append('|');
        if (spec.http() != null) {
            sb.append(norm(spec.http().method())).append('|').append(norm(spec.http().path())).append('|');
            sb.append(sorted(spec.http().params())).append('|').append(norm(spec.http().body())).append('|');
        }
        if (spec.command() != null) {
            sb.append(norm(spec.command().command())).append('|');
        }
        if (spec.assertions() != null) {
            List<String> lines = new ArrayList<>();
            for (AssertionSpec a : spec.assertions()) {
                if (a != null) {
                    lines.add(norm(a.source()) + "|" + norm(a.selector()) + "|" + norm(a.op()) + "|" + norm(a.expected()));
                }
            }
            lines.sort(String::compareTo);
            sb.append(String.join(";", lines));
        }
        return hash(sb.toString());
    }

    /** 旧 CAP-10 用例签名（无 CaseSpec）。 */
    public static String ofLegacy(String kind, String method, String path, String expectedJson) {
        return hash(norm(kind) + "|" + norm(method) + "|" + norm(path) + "|" + norm(expectedJson));
    }

    public static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String sorted(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>(map);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(norm(k)).append('=').append(norm(v)).append('&'));
        return sb.toString();
    }

    private static String norm(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }
}
