package com.devmind.session.service;

import java.util.regex.Pattern;

/**
 * CAP-115 M2 服务端危险命令护栏（双层防线的服务端层）：终端工作区 exec 命中危险模式且
 * 请求未带 {@code confirmed=true} 时抛 {@code DevMindException(NEED_CONFIRM)}（HTTP 428），
 * 前端弹确认框展示完整命令后带 confirmed 重发。runner 侧另有硬阻断（不可确认放行的
 * 毁灭性写法）与白名单兜底，本层职责是「可放行但要用户明确点头」的灰区命令。
 *
 * <p>切段/词法与 runner 侧 TerminalHandler 同口径（|/||/&&/;/换行切段，逐段看首 token）。</p>
 */
public final class DangerousCommandGuard {

    private static final java.util.regex.Pattern SEGMENTS =
            java.util.regex.Pattern.compile("\\R|\\|\\||&&|\\||;");

    private DangerousCommandGuard() {
    }

    /**
     * 命中危险模式返回用户可读原因（含规则说明），未命中返回 null。
     * 规则集：rm -rf、git push --force/-f、git reset --hard、git clean -fd、mkfs、fork 炸弹。
     */
    public static String check(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        for (String seg : SEGMENTS.split(command)) {
            String s = seg.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            if (s.contains(":(){")) {
                return "疑似 fork 炸弹（:(){...}）";
            }
            String[] tokens = s.split("\\s+");
            String first = tokens[0].toLowerCase();
            if (first.equals("mkfs") || first.startsWith("mkfs.")) {
                return "mkfs 格式化命令";
            }
            if ("rm".equals(first)) {
                boolean recursive = false;
                boolean force = false;
                for (int i = 1; i < tokens.length; i++) {
                    String t = tokens[i].toLowerCase();
                    if (t.startsWith("-") && !t.equals("--")) {
                        recursive |= t.contains("r");
                        force |= t.contains("f");
                    }
                }
                if (recursive && force) {
                    return "rm -rf 递归强制删除";
                }
            }
            if ("git".equals(first)) {
                String sub = gitSubcommand(tokens);
                if ("push".equals(sub)) {
                    for (String t : tokens) {
                        if ("--force".equals(t) || "-f".equals(t) || "--force-with-lease".equals(t)) {
                            return "git push 强制推送（" + t + "）会覆盖远端历史";
                        }
                    }
                } else if ("reset".equals(sub)) {
                    for (String t : tokens) {
                        if ("--hard".equals(t)) {
                            return "git reset --hard 丢弃未提交改动";
                        }
                    }
                } else if ("clean".equals(sub)) {
                    boolean f = false;
                    boolean d = false;
                    for (String t : tokens) {
                        if (t.startsWith("-") && !t.equals("--")) {
                            f |= t.contains("f");
                            d |= t.contains("d");
                        }
                    }
                    if (f && d) {
                        return "git clean -fd 删除未跟踪文件与目录";
                    }
                }
            }
        }
        return null;
    }

    /** git 的子命令：跳过全局选项（-C/-c/--git-dir/--work-tree 各吞一个值），取第一个非选项 token。 */
    private static String gitSubcommand(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("-C") || t.equals("-c") || t.equals("--git-dir") || t.equals("--work-tree")) {
                i++;
                continue;
            }
            if (t.startsWith("-")) {
                continue;
            }
            return t;
        }
        return null;
    }
}
