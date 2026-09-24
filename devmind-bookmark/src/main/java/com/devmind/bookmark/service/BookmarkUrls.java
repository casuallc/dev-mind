package com.devmind.bookmark.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;

import java.net.URI;
import java.util.Locale;

/**
 * CAP-64 URL 口径：收藏与探测共用的「仅 http/https」校验 + 私有地址判定。
 * 全仓无公共 URL 校验工具（对照 release/build 模块的就地两行式先例收口到这里）。
 */
final class BookmarkUrls {

    private BookmarkUrls() {
    }

    /** 校验并归一化（trim）；不接受 http/https 之外任何 scheme（含 file/jar/ftp）。 */
    static String normalize(String raw) {
        String url = raw == null ? "" : raw.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "URL 必须以 http:// 或 https:// 开头：" + url);
        }
        String host = hostOf(url);
        if (host == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "URL 缺少主机名：" + url);
        }
        return url;
    }

    /** 取主机名；解析不出返回 null。 */
    static String hostOf(String url) {
        try {
            URI uri = URI.create(url == null ? "" : url.trim());
            String host = uri.getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 是否内网/本机地址（字面 IP 的保留段、localhost、无点主机名）。
     * 只看字面量，不做 DNS 解析——探测的目标是「收藏里的这条地址通不通」，
     * 域名解析到内网与否由出站网络环境决定，不在这里替用户裁决。
     */
    static boolean isPrivateHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.equals("[::1]") || h.equals("::1")) {
            return true;
        }
        if (!h.contains(".")) {
            // 无点主机名只可能是内网短名（public DNS 根域必带点）
            return true;
        }
        return isPrivateIpv4(h) || isPrivateIpv6(h);
    }

    private static boolean isPrivateIpv4(String h) {
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] oct = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("\\d{1,3}")) {
                return false;
            }
            oct[i] = Integer.parseInt(parts[i]);
            if (oct[i] > 255) {
                return false;
            }
        }
        return oct[0] == 10                                // 10.0.0.0/8
                || oct[0] == 127                           // 127.0.0.0/8 回环
                || (oct[0] == 172 && oct[1] >= 16 && oct[1] <= 31)   // 172.16.0.0/12
                || (oct[0] == 192 && oct[1] == 168)        // 192.168.0.0/16
                || (oct[0] == 169 && oct[1] == 254)        // 169.254.0.0/16 链路本地
                || oct[0] == 0;                            // 0.0.0.0/8
    }

    private static boolean isPrivateIpv6(String h) {
        String s = h;
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (!s.contains(":")) {
            return false;
        }
        return s.equals("::1")
                || s.startsWith("fc") || s.startsWith("fd")      // fc00::/7 唯一本地
                || s.startsWith("fe80");                          // 链路本地
    }
}
