package com.devmind.agent.runner;

import com.devmind.common.agent.exec.RunnerWorkspace;
import com.devmind.common.agent.runtime.ProcessHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * CAP-58 terminal_exec 帧 handler（runner 侧会话工作区远程终端）：收
 * terminal_exec{requestId, sessionId, command, cwd} → 白名单校验 → 在会话代码目录
 * （{@link RunnerSessionRegistry#knownDirOf}，终态走 recentDirs）下以 execShell 执行单条命令 →
 * terminal_exec_ack{exitCode, stdout, stderr, cwd} 收口回传。
 *
 * <p><b>cwd 语义</b>：帧 cwd = 相对代码目录的 POSIX 路径（空 = 根，前端持有状态）；
 * 脚本尾部打 {@link #CWD_MARKER} marker 行取 pwd 解析回新 cwd 随 ack 返回
 * （{@code cd frontend && ls} 天然生效）；新 cwd 越界（{@code cd /}、符号链接逃逸）→
 * ok=false 且 cwd 维持原值。</p>
 *
 * <p><b>安全</b>：白名单 runner 本地强制（双层防线的 runner 层）——terminalAllowlist 缺省
 * （属性缺席）= 内置只读档 {@link #DEFAULT_ALLOWLIST}，显式配置（含空串 = 拒绝一切）全覆盖；
 * 按 |/||/&&/;/换行切段逐段校验首 token 前缀命中，shell 内置命令豁免（同 ExecHandler）；
 * git 默认只放行只读子命令 {@link #GIT_READONLY_SUBCOMMANDS}（条目 {@code git:*} 全放行）；
 * {@code >}/{@code >>} 重定向默认拒绝（terminalAllowRedirect=true 放开）。cwd 限定复用
 * {@link WorkspaceQueryHandler#resolveConfined}（startsWith + toRealPath 双防逃逸）。</p>
 */
public class TerminalHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalHandler.class);

    /** stdout/stderr 各自上限（超了保留尾部；WS 文本帧缓冲 512KB，CAP-54 已调） */
    static final int OUTPUT_CAP = 128 * 1024;

    /** 脚本尾部取 pwd 的 marker 前缀（值紧随其后到行尾） */
    static final String CWD_MARKER = "__DEVMIND_CWD__:";

    /** 内置只读档（terminalAllowlist 未配置时生效） */
    static final List<String> DEFAULT_ALLOWLIST = List.of(
            "ls", "dir", "pwd", "cat", "type", "head", "tail", "find", "grep", "echo",
            "wc", "du", "df", "tree", "which", "where", "git");

    /** git 默认放行的只读子命令 */
    static final Set<String> GIT_READONLY_SUBCOMMANDS = Set.of(
            "status", "diff", "log", "show", "branch", "remote", "rev-parse", "ls-files",
            "blame", "grep", "tag", "describe", "shortlog", "reflog", "config");

    /** 重定向写入探测：裸 > / >> 与 &> / &>>（2>&1 流合并不算） */
    private static final Pattern REDIRECT = Pattern.compile("(?<![0-9&>])>{1,2}(?![>&])|&>{1,2}");

    /** 切段：管道/逻辑与或/分号/换行 */
    private static final Pattern SEGMENTS = Pattern.compile("\\R|\\|\\||&&|\\||;");

    private final RunnerConfig config;
    private final RunnerSessionRegistry sessions;
    private final Consumer<Map<String, Object>> sender;
    /** requestId → 运行中的进程（shutdown killAll 用） */
    private final Map<String, Process> active = new ConcurrentHashMap<>();

    public TerminalHandler(RunnerConfig config, RunnerSessionRegistry sessions,
                           Consumer<Map<String, Object>> sender) {
        this.config = config;
        this.sessions = sessions;
        this.sender = sender;
    }

    /** WS listener 线程入口：立即投虚拟线程，帧处理不阻塞收帧。 */
    public void handle(JsonNode frame) {
        Thread.ofVirtual().name("term-" + frame.path("requestId").asText("?")).start(() -> run(frame));
    }

    /** shutdown hook：整树杀光运行中的终端命令进程。 */
    public void killAll() {
        for (Process p : active.values()) {
            ProcessHelper.killTree(p);
        }
    }

    private void run(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        String sessionId = frame.path("sessionId").asText("");
        String command = frame.path("command").asText("");
        String cwd = frame.path("cwd").asText("");
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "terminal_exec_ack");
        ack.put("requestId", requestId);
        try {
            Path base = sessions.knownDirOf(sessionId).orElseThrow(() ->
                    new IllegalStateException("会话不在本节点运行或工作区已释放（runner 重启后终态会话目录不可定位）"));
            checkAllowlist(config.terminalAllowlist(), config.terminalAllowRedirect(), command);
            Path cwdAbs = WorkspaceQueryHandler.resolveConfined(base, cwd, true);
            if (!Files.isDirectory(cwdAbs)) {
                throw new IllegalStateException("cwd 不是目录: " + (cwd.isBlank() ? "/" : cwd));
            }

            // 脚本：进 cwd → 跑命令 → marker 行取新 pwd（命令里 cd 也生效）。无 set -e——
            // 命令非零退出不能跳过 marker；命令含 exit 内建会提前终结脚本（marker 缺席，cwd 维持原值）
            boolean windows = java.io.File.separatorChar == '\\';
            String pwdCmd = windows ? "pwd -W" : "pwd"; // MSYS bash 的 pwd 是 /c/ 形式，-W 出 Windows 路径
            String script = "cd \"" + cwdAbs.toString().replace('\\', '/') + "\" || exit 97\n"
                    + command + "\n__rc=$?\necho \"" + CWD_MARKER + "$(" + pwdCmd + ")\"\nexit $__rc\n";

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            int code;
            boolean timedOut = false;
            Path tmp = null;
            Process proc = null;
            try {
                tmp = Files.createTempFile("devmind-term-", ".sh");
                Files.writeString(tmp, script, StandardCharsets.UTF_8);
                ProcessBuilder pb = new ProcessBuilder(config.execShell(), tmp.toAbsolutePath().toString());
                pb.directory(cwdAbs.toFile());
                proc = pb.start();
                active.put(requestId, proc);
                Process p = proc;
                Thread outT = Thread.ofVirtual().name("term-out-" + requestId)
                        .start(() -> pump(p.getInputStream(), out));
                Thread errT = Thread.ofVirtual().name("term-err-" + requestId)
                        .start(() -> pump(p.getErrorStream(), err));
                if (!proc.waitFor(config.terminalTimeoutSec(), TimeUnit.SECONDS)) {
                    timedOut = true;
                    append(err, "[runner] 命令超时（>" + config.terminalTimeoutSec() + "s），整树终止");
                    ProcessHelper.killTree(proc);
                    proc.waitFor(10, TimeUnit.SECONDS);
                }
                outT.join(5000);
                errT.join(5000);
                code = proc.exitValue();
            } finally {
                active.remove(requestId);
                if (proc != null && proc.isAlive()) {
                    ProcessHelper.killTree(proc);
                }
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (Exception ignored) {
                    }
                }
            }

            String stdout = RunnerWorkspace.sanitize(out.toString(), null);
            String stderr = RunnerWorkspace.sanitize(err.toString(), null);
            // marker 可能在最后一行中段（命令输出没换行）：取最后出现处到行尾为值，并从输出里剥掉
            String markerCwd = null;
            int mi = stdout.lastIndexOf(CWD_MARKER);
            if (mi >= 0) {
                int eol = stdout.indexOf('\n', mi);
                markerCwd = stdout.substring(mi + CWD_MARKER.length(), eol < 0 ? stdout.length() : eol).strip();
                stdout = (stdout.substring(0, mi) + (eol < 0 ? "" : stdout.substring(eol + 1)))
                        .replaceAll("\\s+$", "");
            }
            if (code == 97) {
                throw new IllegalStateException("cwd 不存在或已释放: " + (cwd.isBlank() ? "/" : cwd));
            }

            String newCwd = cwd; // marker 缺席（命令含 exit 内建等）→ cwd 维持原值
            if (markerCwd != null) {
                Path resolved = Path.of(markerCwd).toAbsolutePath().normalize();
                Path realBase = base.toRealPath();
                if (!resolved.toRealPath().startsWith(realBase)) {
                    throw new IllegalStateException("cd 越界（代码目录外/符号链接逃逸）: " + markerCwd);
                }
                newCwd = realBase.relativize(resolved.toRealPath()).toString().replace('\\', '/');
            }
            ack.put("ok", true);
            ack.put("exitCode", code);
            ack.put("stdout", stdout);
            ack.put("stderr", stderr);
            ack.put("cwd", newCwd);
            if (timedOut) {
                ack.put("timedOut", true);
            }
        } catch (Exception e) {
            log.debug("terminal_exec 失败: session={} err={}", sessionId, e.getMessage());
            ack.put("ok", false);
            ack.put("error", String.valueOf(e.getMessage()));
        }
        sender.accept(ack);
    }

    /**
     * terminalAllowlist 校验（纯函数，可单测）：configured == null = 缺省只读档；显式配置
     * （含空表 = 拒绝一切）全覆盖。切段逐段取首 token 前缀命中；git 走子命令白名单，
     * 条目 {@code git:*} 放行全部子命令；重定向写入默认拒绝。
     */
    static void checkAllowlist(List<String> configured, boolean allowRedirect, String command) {
        List<String> allowed = configured != null ? configured : DEFAULT_ALLOWLIST;
        if (allowed.isEmpty()) {
            throw new IllegalStateException("runner terminalAllowlist 为空，拒绝一切终端命令");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("terminal_exec 帧 command 为空");
        }
        if (!allowRedirect && REDIRECT.matcher(command).find()) {
            throw new IllegalStateException("重定向写入（>/>>）不在默认放行范围"
                    + "（节点配置 terminalAllowRedirect=true 可放开）");
        }
        boolean gitAll = allowed.stream().anyMatch("git:*"::equals);
        List<String> offenders = new ArrayList<>();
        for (String seg : SEGMENTS.split(command)) {
            String stripped = seg.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            String[] tokens = stripped.split("\\s+");
            String first = tokens[0];
            String lower = first.toLowerCase();
            if (ExecHandler.SHELL_BUILTINS.contains(lower)) {
                continue;
            }
            boolean hit = allowed.stream().anyMatch(a -> !a.endsWith(":*")
                    && (first.equals(a) || first.startsWith(a)));
            if ("git".equalsIgnoreCase(first)) {
                if (gitAll) {
                    hit = true;
                } else if (hit) {
                    String sub = gitSubcommand(tokens);
                    if (sub == null || !GIT_READONLY_SUBCOMMANDS.contains(sub.toLowerCase())) {
                        offenders.add("git " + (sub == null ? "?" : sub));
                        continue;
                    }
                }
            }
            if (!hit) {
                offenders.add(first);
            }
        }
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("命令不在 terminalAllowlist 白名单: " + String.join(", ", offenders)
                    + "（允许前缀: " + String.join(", ", allowed) + "）");
        }
    }

    /** git 的子命令：跳过全局选项（-C/-c/--git-dir/--work-tree 各吞一个值），取第一个非选项 token。 */
    private static String gitSubcommand(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("-C") || t.equals("-c") || t.equals("--git-dir") || t.equals("--work-tree")) {
                i++; // 吞掉选项值
                continue;
            }
            if (t.startsWith("-")) {
                continue;
            }
            return t;
        }
        return null;
    }

    /** 行式读流追加到缓冲（超两倍上限即截头，防巨量输出撑内存）。 */
    private void pump(InputStream in, StringBuilder sb) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                append(sb, line);
            }
        } catch (IOException ignored) {
        }
    }

    private static void append(StringBuilder sb, String line) {
        synchronized (sb) {
            sb.append(line).append('\n');
            if (sb.length() > OUTPUT_CAP * 2) {
                sb.delete(0, sb.length() - OUTPUT_CAP);
                sb.insert(0, "[runner] 输出过长，仅保留尾部 " + OUTPUT_CAP + " 字符\n");
            }
        }
    }
}
