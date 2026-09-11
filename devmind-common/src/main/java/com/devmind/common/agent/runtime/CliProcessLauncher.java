package com.devmind.common.agent.runtime;

import com.devmind.common.agent.InputImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 真实 Claude Code 执行器（stream-json 双端流）：
 * {@code claude -p --input-format stream-json --output-format stream-json --verbose --permission-mode <mode>}
 *
 * <p>CLI 参数与 schema 随版本变化——本类与 {@link CliEventParser} 是仅有的接触点。Windows 下
 * claude 多为 {@code .cmd} 包装，需经 {@code cmd.exe /c} 启动；路径可在配置指定，否则按平台探测（Windows=where / Linux·macOS=which）。</p>
 *
 * <p>CAP-30 起为纯工具类（不再是 Spring 组件）：Spring 侧由能力模块的 @Configuration 以
 * {@link RuntimeSettings} + {@link ObjectMapper} 装配 Bean；runner（无 Spring）直接 new。</p>
 *
 * <p>⚠️ 2026-08-30 spike 实测（claude 2.1.250）：</p>
 * <ul>
 *   <li>stream-json 输出必须加 {@code --verbose}，否则报错退出；</li>
 *   <li>交互输入格式是完整 user message：{@code {"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]}}}，
 *       而非 {@code {"type":"input",...}}（后者被静默忽略→零输出）；</li>
 *   <li>初始 prompt 作为第一条 user message 写入 stdin（不再作为位置参数）；stdin 保持打开供后续注入。</li>
 * </ul>
 */
public class CliProcessLauncher implements SessionExecutor {

    private static final Logger log = LoggerFactory.getLogger(CliProcessLauncher.class);

    private final RuntimeSettings settings;
    private final ObjectMapper mapper;
    private volatile String resolvedPath;

    public CliProcessLauncher(RuntimeSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "claude";
    }

    @Override
    public Process launch(LaunchContext ctx) throws IOException {
        List<String> cmd = buildCommand(ctx);
        Path cwd = ctx.worktree() != null ? ctx.worktree() : Path.of("").toAbsolutePath();
        log.info("启动 claude 会话: {}  cwd={}  cmd={}", ctx.sessionId(), cwd, cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        // CAP-24：提交身份等附加 env 随进程注入（不写 git config，避免 worktree 共享配置互踩）
        if (ctx.env() != null && !ctx.env().isEmpty()) {
            pb.environment().putAll(ctx.env());
        }
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // 初始 prompt 作为第一条 user message 写入 stdin；--resume 续接时对话历史已含原任务，
        // 再写一遍等于重复下达 → 续接拉起不注入 taskSpec（场景重渲染的 renderedTask 同理跳过）
        if (ctx.resumeSessionId() == null || ctx.resumeSessionId().isBlank()) {
            if (ctx.taskSpec() != null && !ctx.taskSpec().isBlank()) {
                writeUserMessage(process.getOutputStream(), ctx.taskSpec());
            }
        }
        return process;
    }

    /** 初始 prompt 以 stream-json user message 形式写入 stdin。runner（CAP-21）复用本方法包装交互输入。 */
    public void writeUserMessage(OutputStream out, String text) throws IOException {
        out.write((buildUserMessage(text) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 构造 stream-json user message 行（不含换行）。 */
    public String buildUserMessage(String text) {
        return buildUserMessage(mapper, text, List.of());
    }

    /** CAP-32：构造带图片附件的 stream-json user message 行。runner（CAP-21）input 帧带图时复用。 */
    public String buildUserMessage(String text, List<InputImage> images) {
        return buildUserMessage(mapper, text, images);
    }

    /**
     * 组帧核心（静态，供 {@link SessionRuntime} 等无 launcher 实例方复用）：
     * image content blocks 在前、text block 在后；皆空时补空 text block 保证 content 非空。
     */
    public static String buildUserMessage(ObjectMapper mapper, String text, List<InputImage> images) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (images != null) {
            for (InputImage img : images) {
                content.add(Map.of("type", "image", "source",
                        Map.of("type", "base64", "media_type", img.mediaType(), "data", img.base64Data())));
            }
        }
        if (text != null && !text.isBlank()) {
            content.add(Map.of("type", "text", "text", text));
        }
        if (content.isEmpty()) {
            content.add(Map.of("type", "text", "text", ""));
        }
        Map<String, Object> message = Map.of("role", "user", "content", content);
        Map<String, Object> line = Map.of("type", "user", "message", message);
        try {
            return mapper.writeValueAsString(line);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 构造 permission_result 行（不含换行）；accepted + scope 时附 scope。 */
    public String buildPermissionResult(String requestId, boolean accepted, String scope) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "permission_result");
        payload.put("permission_request_id", requestId);
        payload.put("permission", accepted ? "allow" : "deny");
        if (accepted && scope != null && !scope.isBlank()) {
            payload.put("scope", scope);
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    List<String> buildCommand(LaunchContext ctx) {
        String claude = resolvePath();
        List<String> cmd = new ArrayList<>();
        if (claude.toLowerCase().endsWith(".cmd") || claude.toLowerCase().endsWith(".bat")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(claude);
        cmd.add("-p");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--permission-mode");
        cmd.add(ctx.permissionMode() == null || ctx.permissionMode().isBlank()
                ? settings.defaultPermissionMode() : ctx.permissionMode());
        if (ctx.model() != null && !ctx.model().isBlank()) {
            cmd.add("--model");
            cmd.add(ctx.model());
        }
        // 续接既有 CLI 会话（对话历史在 CLI 配置目录按 cwd 归档）；id 失效时 CLI 报错退出
        if (ctx.resumeSessionId() != null && !ctx.resumeSessionId().isBlank()) {
            cmd.add("--resume");
            cmd.add(ctx.resumeSessionId());
        }
        return cmd;
    }

    /** claude 路径：配置优先，空则按平台探测（Windows=where，其余=which，结果缓存）。 */
    String resolvePath() {
        if (settings.claudePath() != null && !settings.claudePath().isBlank()) {
            return settings.claudePath().strip();
        }
        if (resolvedPath != null) {
            return resolvedPath;
        }
        // where 是 Windows 独有命令，Linux/macOS 直接用 which（否则探测必败、回退裸命令 error=2）
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        try {
            Process p = new ProcessBuilder(windows ? "where" : "which", "claude").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor(5, TimeUnit.SECONDS)) {
                String first = out.lines().map(String::strip).filter(l -> !l.isBlank()).findFirst().orElse(null);
                if (first != null) {
                    resolvedPath = first;
                    return first;
                }
            }
        } catch (Exception e) {
            log.warn("探测 claude 路径失败，回退用裸命令: {}", e.getMessage());
        }
        return "claude";
    }
}
