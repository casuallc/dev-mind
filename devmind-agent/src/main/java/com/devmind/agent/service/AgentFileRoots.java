package com.devmind.agent.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.regex.Pattern;

/**
 * CAP-65 文件访问根目录白名单编解码与校验（agent_nodes.file_roots JSON 数组串 ↔ List）。
 * 服务端 DB 为唯一权威：每个 file 帧携带 roots 全量下发，runner 不做本地配置。
 *
 * <p>绝对路径判定跨平台（服务端与节点可能异 OS，不能用 Path.isAbsolute 本机语义）：
 * Windows 盘符（D:/ 或 D:\）、UNC（\\server\share）、POSIX（/）三形态均认。</p>
 */
public final class AgentFileRoots {

    /** 白名单条数上限 */
    public static final int MAX_ROOTS = 16;
    /** 单条路径长度上限 */
    public static final int MAX_ROOT_LEN = 240;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** Windows 盘符 / UNC / POSIX 三形态绝对路径 */
    private static final Pattern ABSOLUTE = Pattern.compile("^([A-Za-z]:[\\\\/]|/|\\\\\\\\).*");
    /** 控制字符（含换行/NUL）一律拒绝 */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");

    private AgentFileRoots() {
    }

    /** 校验并归一化（逐条 trim 去空白条目）；非法抛 BAD_REQUEST。空表合法（= 清空）。 */
    public static List<String> validate(List<String> roots) {
        if (roots == null) {
            return null;
        }
        List<String> cleaned = roots.stream()
                .map(r -> r == null ? "" : r.strip())
                .filter(r -> !r.isEmpty())
                .distinct()
                .toList();
        if (cleaned.size() > MAX_ROOTS) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "文件访问根目录条数超限（" + cleaned.size() + " > " + MAX_ROOTS + "）");
        }
        for (String r : cleaned) {
            if (r.length() > MAX_ROOT_LEN) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "文件访问根目录超长（" + r.length() + " > " + MAX_ROOT_LEN + " 字符）: " + r);
            }
            if (CONTROL.matcher(r).find()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "文件访问根目录含非法控制字符: " + r);
            }
            if (!ABSOLUTE.matcher(r).matches()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "文件访问根目录须为绝对路径（D:/ 或 / 开头）: " + r);
            }
        }
        return cleaned;
    }

    /** 序列化落库：空表/null → null（清空语义）。 */
    public static String toJson(List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return null;
        }
        return MAPPER.writeValueAsString(roots);
    }

    /** 解析库值：null/空白/坏 JSON → 空表（坏数据按未配置对待，文件浏览不可用）。 */
    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> roots = MAPPER.readValue(json, new TypeReference<>() {
            });
            return roots == null ? List.of() : roots;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 匹配归一化（与 runner 侧 FileHandler 同口径，双侧各自实现）：统一 \ → /、去尾分隔符、
     * Windows 盘符大小写不敏感。
     */
    public static String normalize(String root) {
        if (root == null) {
            return "";
        }
        String r = root.strip().replace('\\', '/');
        while (r.length() > 1 && r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        // 盘符大小写不敏感（D:/x ≡ d:/x）；UNC 与 POSIX 不动
        if (r.length() >= 2 && Character.isLetter(r.charAt(0)) && r.charAt(1) == ':') {
            r = Character.toLowerCase(r.charAt(0)) + r.substring(1);
        }
        return r;
    }

    /** root 是否命中白名单（归一化后精确匹配）。 */
    public static boolean containsRoot(List<String> roots, String root) {
        String target = normalize(root);
        return roots.stream().map(AgentFileRoots::normalize).anyMatch(target::equals);
    }
}
