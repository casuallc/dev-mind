package com.devmind.agent.runner;

import com.devmind.common.agent.exec.RunnerWorkspace;
import com.devmind.common.agent.runtime.ProcessHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * CAP-58/59 terminal_* 帧 handler（runner 侧会话工作区远程终端）：
 * <ul>
 * <li>{@code terminal_exec{requestId, sessionId, command, cwd}} → 白名单校验 → 该会话的
 * <b>持久 shell</b>（CAP-59 {@link PersistentShell}，懒起/死亡自动重启/空闲回收）执行 →
 * {@code terminal_exec_ack{exitCode, stdout, stderr, cwd, timedOut?, cancelled?}} 收口回传；</li>
 * <li>{@code terminal_complete{requestId, sessionId, input, cwd}} → 一次性 execShell 子进程在
 * 会话当前 cwd 下 compgen 取候选（不走持久 shell，不与执行中的命令争 stdin）→
 * {@code terminal_complete_ack{word, candidates[]}}；</li>
 * <li>{@code terminal_cancel{sessionId}} → 整树杀该会话执行中的命令（shell 随杀，exec 以
 * cancelled=true 收口，下条命令自动重启 shell）。</li>
 * </ul>
 *
 * <p><b>cwd 语义（CAP-59 起权威移到 runner）</b>：shell 的 cwd 即状态——export/别名/cd 跨命令
 * 保持；ack 回传 CWD 哨兵解析的新 cwd，前端跟随。帧 cwd 仅在 shell（重）首启时作初始目录
 * （runner 侧 lastCwd 优先于帧 cwd）。cd 越界（CWD 哨兵出界/符号链接逃逸）→ ok=false 且
 * shell 被强制 cd 回代码目录（持久 shell 里 cd 已真实发生，必须拉回）。</p>
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

    /** 补全候选上限 */
    static final int COMPLETE_CAP = 100;

    /** 补全一次性子进程超时（秒） */
    private static final int COMPLETE_TIMEOUT_SEC = 5;

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

    /** 补全输出行首的种类标记（\x01 防与候选内容碰撞）：F=文件 D=目录 */
    private static final String SEC_FILES = "__DEVMIND_CMP_FILES__";
    private static final String SEC_DIRS = "__DEVMIND_CMP_DIRS__";

    private final RunnerConfig config;
    private final RunnerSessionRegistry sessions;
    private final Consumer<Map<String, Object>> sender;

    /** 会话终端状态：持久 shell + 命令串行锁 + runner 侧 cwd（权威）。 */
    private static final class ShellSlot {
        final PersistentShell shell;
        final Object lock = new Object();
        volatile String lastCwd; // 相对代码目录 POSIX 路径（空 = 根）

        ShellSlot(PersistentShell shell) {
            this.shell = shell;
        }
    }

    /** sessionId → 终端 slot */
    private final Map<String, ShellSlot> slots = new ConcurrentHashMap<>();
    /** requestId → 补全一次性进程（shutdown killAll 用） */
    private final Map<String, Process> activeCompletes = new ConcurrentHashMap<>();
    /** 空闲 shell 回收巡检 */
    private final ScheduledExecutorService reaper;

    public TerminalHandler(RunnerConfig config, RunnerSessionRegistry sessions,
                           Consumer<Map<String, Object>> sender) {
        this.config = config;
        this.sessions = sessions;
        this.sender = sender;
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "terminal-shell-reaper");
            t.setDaemon(true);
            return t;
        });
        this.reaper.scheduleWithFixedDelay(this::reapIdleShells, 1, 1, TimeUnit.MINUTES);
    }

    /** WS listener 线程入口：立即投虚拟线程，帧处理不阻塞收帧。 */
    public void handle(JsonNode frame) {
        Thread.ofVirtual().name("term-" + frame.path("requestId").asText("?")).start(() -> run(frame));
    }

    /** CAP-59：terminal_complete 帧入口（虚拟线程，同 exec）。 */
    public void handleComplete(JsonNode frame) {
        Thread.ofVirtual().name("term-cmp-" + frame.path("requestId").asText("?"))
                .start(() -> runComplete(frame));
    }

    /** CAP-59：terminal_cancel 帧入口（fire-and-forget，直接同步处理——只置标志+杀进程，毫秒级）。 */
    public void handleCancel(JsonNode frame) {
        String sessionId = frame.path("sessionId").asText("");
        ShellSlot slot = slots.get(sessionId);
        if (slot != null) {
            slot.shell.cancel();
        }
    }

    /** shutdown hook：整树杀光全部持久 shell 与补全进程，停回收巡检。 */
    public void killAll() {
        reaper.shutdownNow();
        for (ShellSlot slot : slots.values()) {
            slot.shell.destroyQuietly();
        }
        slots.clear();
        for (Process p : activeCompletes.values()) {
            ProcessHelper.killTree(p);
        }
    }

    /** 空闲超 terminalShellIdleMin 的 shell 整树回收（slot 与 lastCwd 保留，下条命令重启）。 */
    private void reapIdleShells() {
        try {
            long idleMs = TimeUnit.MINUTES.toMillis(Math.max(1, config.terminalShellIdleMin()));
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ShellSlot> e : slots.entrySet()) {
                ShellSlot slot = e.getValue();
                if (slot.shell.isAlive() && now - slot.shell.lastUsed() > idleMs) {
                    log.info("回收空闲持久 shell: session={} idle>{}min", e.getKey(), config.terminalShellIdleMin());
                    slot.shell.destroyQuietly();
                }
            }
        } catch (Exception e) {
            log.debug("持久 shell 空闲回收巡检异常: {}", e.getMessage());
        }
    }

    // ---------------- terminal_exec ----------------

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
            ShellSlot slot = slots.computeIfAbsent(sessionId,
                    k -> new ShellSlot(new PersistentShell(config.execShell(), base)));
            // 同会话命令串行（单 stdin）；cancel 不经此锁（只杀进程，即时生效）
            synchronized (slot.lock) {
                execInSlot(slot, base, sessionId, command, cwd, ack);
            }
        } catch (Exception e) {
            log.debug("terminal_exec 失败: session={} err={}", sessionId, e.getMessage());
            ack.put("ok", false);
            ack.put("error", String.valueOf(e.getMessage()));
        }
        sender.accept(ack);
    }

    private void execInSlot(ShellSlot slot, Path base, String sessionId, String command, String frameCwd,
                            Map<String, Object> ack) throws Exception {
        // shell（重）首启的初始目录：runner 侧 lastCwd 优先（权威状态），缺省用帧 cwd
        if (!slot.shell.isAlive()) {
            String initial = slot.lastCwd != null ? slot.lastCwd : (frameCwd == null ? "" : frameCwd);
            Path cwdAbs = WorkspaceQueryHandler.resolveConfined(base, initial, true);
            if (!Files.isDirectory(cwdAbs)) {
                throw new IllegalStateException("cwd 不是目录: " + (initial.isBlank() ? "/" : initial));
            }
            slot.shell.ensureStarted(cwdAbs);
        }
        PersistentShell.Outcome o = slot.shell.exec(command, config.terminalTimeoutSec());

        String stdout = RunnerWorkspace.sanitize(o.stdout(), null);
        String stderr = RunnerWorkspace.sanitize(o.stderr(), null);
        String newCwd = slot.lastCwd != null ? slot.lastCwd : (frameCwd == null ? "" : frameCwd);
        if (o.cwd() != null) {
            // CWD 哨兵越界 → 拒绝 + shell 强制拉回代码目录（持久 shell 里 cd 已真实发生）
            Path resolved = Path.of(o.cwd()).toAbsolutePath().normalize();
            Path realBase = base.toRealPath();
            if (!resolved.toRealPath().startsWith(realBase)) {
                slot.shell.cdHome();
                slot.lastCwd = "";
                throw new IllegalStateException("cd 越界（代码目录外/符号链接逃逸）: " + o.cwd()
                        + "（shell 已强制拉回代码目录）");
            }
            newCwd = realBase.relativize(resolved.toRealPath()).toString().replace('\\', '/');
        }
        slot.lastCwd = newCwd;
        ack.put("ok", true);
        ack.put("exitCode", o.exitCode());
        ack.put("stdout", stdout);
        ack.put("stderr", stderr);
        ack.put("cwd", newCwd);
        if (o.timedOut()) {
            ack.put("timedOut", true);
        }
        if (o.cancelled()) {
            ack.put("cancelled", true);
        }
        if (o.shellDied() && !o.timedOut() && !o.cancelled()) {
            log.debug("持久 shell 随命令死亡（exit 内建/被杀）: session={}，下条命令自动重启", sessionId);
        }
    }

    // ---------------- terminal_complete ----------------

    private void runComplete(JsonNode frame) {
        String requestId = frame.path("requestId").asText("");
        String sessionId = frame.path("sessionId").asText("");
        String input = frame.path("input").asText("");
        String cwd = frame.path("cwd").asText("");
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "terminal_complete_ack");
        ack.put("requestId", requestId);
        try {
            Path base = sessions.knownDirOf(sessionId).orElseThrow(() ->
                    new IllegalStateException("会话不在本节点运行或工作区已释放"));
            // 补全目录：runner 侧 lastCwd 优先（与 exec 同口径），缺省帧 cwd
            ShellSlot slot = slots.get(sessionId);
            String effectiveCwd = slot != null && slot.lastCwd != null ? slot.lastCwd
                    : (cwd == null ? "" : cwd);
            Path cwdAbs = WorkspaceQueryHandler.resolveConfined(base, effectiveCwd, true);
            if (!Files.isDirectory(cwdAbs)) {
                cwdAbs = base.toRealPath();
            }
            CompleteParse parse = parseCompletion(input);
            List<String> candidates = runCompgen(cwdAbs, parse, requestId);
            ack.put("ok", true);
            ack.put("word", parse.word());
            ack.put("candidates", candidates);
        } catch (Exception e) {
            log.debug("terminal_complete 失败: session={} err={}", sessionId, e.getMessage());
            ack.put("ok", false);
            ack.put("error", String.valueOf(e.getMessage()));
        }
        sender.accept(ack);
    }

    /** 补全解析结果：word = 被补全的词；mode = c（命令）/ d（仅目录）/ f（文件+目录）。 */
    record CompleteParse(String word, String mode) {
    }

    /**
     * 补全解析（纯函数，可单测）：行尾空白 = 新词位；首词位补命令；命令为 cd 只补目录；
     * 其余补文件+目录。管道/分号后的段视为新命令（取最后一段判断）。
     */
    static CompleteParse parseCompletion(String input) {
        if (input == null || input.isBlank()) {
            return new CompleteParse("", "c");
        }
        boolean newWord = Character.isWhitespace(input.charAt(input.length() - 1));
        String trimmed = input.strip();
        // 取最后一个管道/分号段作为当前命令行（`cat a | gre` 补的是 gre 段）
        String[] segs = trimmed.split("\\|\\||&&|[|;]");
        String seg = segs[segs.length - 1].strip();
        if (seg.isEmpty()) {
            return new CompleteParse("", "c");
        }
        String[] tokens = seg.split("\\s+");
        String word = newWord ? "" : tokens[tokens.length - 1];
        boolean firstWord = tokens.length == 1 && !newWord;
        if (firstWord) {
            return new CompleteParse(word, "c");
        }
        if ("cd".equals(tokens[0])) {
            return new CompleteParse(word, "d");
        }
        return new CompleteParse(word, "f");
    }

    /** 一次性 execShell 子进程跑 compgen（不走持久 shell，不与执行中的命令争 stdin）。 */
    private List<String> runCompgen(Path cwdAbs, CompleteParse parse, String requestId)
            throws IOException, InterruptedException {
        String w = escapeDq(parse.word());
        String script = "cd \"" + cwdAbs.toString().replace('\\', '/') + "\" || exit 97\n";
        switch (parse.mode()) {
            case "c" -> script += "compgen -c -- \"" + w + "\"\n";
            case "d" -> script += "echo '" + SEC_DIRS + "'\ncompgen -d -- \"" + w + "\"\n";
            default -> script += "echo '" + SEC_FILES + "'\ncompgen -f -- \"" + w + "\"\n"
                    + "echo '" + SEC_DIRS + "'\ncompgen -d -- \"" + w + "\"\n";
        }
        Path tmp = Files.createTempFile("devmind-term-cmp-", ".sh");
        List<String> lines = new ArrayList<>();
        Process proc = null;
        try {
            Files.writeString(tmp, script, StandardCharsets.UTF_8);
            proc = new ProcessBuilder(config.execShell(), tmp.toAbsolutePath().toString()).start();
            activeCompletes.put(requestId, proc);
            Process p = proc;
            Thread outT = Thread.ofVirtual().name("term-cmp-out-" + requestId).start(() -> {
                try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (lines) {
                            lines.add(line);
                        }
                    }
                } catch (IOException ignored) {
                }
            });
            if (!proc.waitFor(COMPLETE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                ProcessHelper.killTree(proc);
                throw new IllegalStateException("补全超时（>" + COMPLETE_TIMEOUT_SEC + "s）");
            }
            outT.join(2000);
        } finally {
            activeCompletes.remove(requestId);
            if (proc != null && proc.isAlive()) {
                ProcessHelper.killTree(proc);
            }
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
        }
        // 目录候选带 / 后缀；同一名字文件/目录都有时保留目录标记版；去重排序截断
        Set<String> merged = new LinkedHashSet<>();
        List<String> dirs = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        boolean inDirs = false;
        synchronized (lines) {
            for (String line : lines) {
                if (SEC_FILES.equals(line)) {
                    inDirs = false;
                } else if (SEC_DIRS.equals(line)) {
                    inDirs = true;
                } else if (inDirs) {
                    dirs.add(line.endsWith("/") ? line : line + "/");
                } else {
                    plain.add(line);
                }
            }
        }
        merged.addAll(dirs);
        for (String f : plain) {
            if (!merged.contains(f) && !merged.contains(f + "/")) {
                merged.add(f);
            }
        }
        return merged.stream().sorted().limit(COMPLETE_CAP).toList();
    }

    /** 双引号内嵌转义（compgen 词嵌入临时脚本用）。 */
    private static String escapeDq(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("`", "\\`");
    }

    // ---------------- 白名单（CAP-58 原样保留） ----------------

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
}
