package com.devmind.security.parse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maven {@code dependency:tree -DoutputFile} 文本解析（CAP-66 FR-11-1 引入链）。
 *
 * <p>树形行形如 {@code [INFO] +- group:artifact:packaging:version:scope}，
 * 层级由 {@code +- }/{@code |  }/{@code \\  } 缩进前缀表达（每级 3 字符）。
 * 输出按 group:artifact 索引：scope、是否直接依赖、引入链（根直接依赖 → … → 自身 GA 列表）。
 * 多模块树（-am）同一 GA 可能多次出现，保留首个（最浅）路径。
 * Gradle 输出格式不解析（v1 仅存原始附件，depPath 为空由修复指导明示）。</p>
 */
public final class DepTreeParser {

    /** 单个构件的依赖树信息 */
    public record DepInfo(String scope, boolean direct, List<String> path) {
    }

    private DepTreeParser() {
    }

    /**
     * @param treeText dependency:tree 输出全文
     * @return key = "groupId:artifactId"
     */
    public static Map<String, DepInfo> parseMavenTree(String treeText) {
        Map<String, DepInfo> out = new HashMap<>();
        if (treeText == null || treeText.isBlank()) {
            return out;
        }
        // 路径栈：depth → GA（depth 0 = 根模块自身）
        List<String> stack = new ArrayList<>();
        for (String rawLine : treeText.split("\\R")) {
            String line = rawLine;
            int infoIdx = line.indexOf("[INFO]");
            if (infoIdx >= 0) {
                line = line.substring(infoIdx + "[INFO]".length());
            }
            if (line.isBlank()) {
                continue;
            }
            String stripped = line.strip();
            // 树节点行：节点标记 "+- " / "\- " 可带续行前缀（"|  " 每级 3 字符），
            // 故不能只看 strip 后开头——前缀区只允许树形字符（|、\、空格）
            int depth;
            String coords;
            int plus = line.indexOf("+- ");
            int back = line.indexOf("\\- ");
            int nodeIdx = plus < 0 ? back : back < 0 ? plus : Math.min(plus, back);
            if (nodeIdx >= 0 && line.substring(0, nodeIdx).chars()
                    .allMatch(c -> c == '|' || c == '\\' || c == ' ')) {
                // 前缀区（不含节点标记 3 字符）每 3 字符一层
                depth = nodeIdx / 3 + 1;
                coords = line.substring(nodeIdx + 3).strip();
            } else if (!stripped.contains(" ") && stripped.contains(":")) {
                depth = 0;
                coords = stripped;
            } else {
                continue;
            }
            // "(g:a:jar:v - omitted for duplicate/conflict)" 省略节点不入索引（也非真实引入路径）
            if (coords.startsWith("(")) {
                continue;
            }
            // 去掉 dependency:tree 的附加标注（如 "(optional)"）
            int paren = coords.indexOf(' ');
            if (paren > 0) {
                coords = coords.substring(0, paren);
            }
            String[] seg = coords.split(":");
            if (seg.length < 4) {
                continue;
            }
            String groupId = seg[0];
            String artifactId = seg[1];
            String scope = seg.length >= 5 ? seg[seg.length - 1] : null;
            String ga = groupId + ":" + artifactId;
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            if (stack.size() == depth) {
                stack.add(ga);
            } else {
                // 缩进异常（跳级）：截断到当前深度后补齐，路径尽力而为
                while (stack.size() < depth) {
                    stack.add(stack.get(stack.size() - 1));
                }
                stack.add(ga);
            }
            if (depth == 0) {
                continue; // 根模块自身不入索引
            }
            if (out.containsKey(ga)) {
                continue; // 保留最浅路径
            }
            List<String> path = List.copyOf(stack.subList(1, stack.size()));
            out.put(ga, new DepInfo(scope, depth == 1, path));
        }
        return out;
    }
}
