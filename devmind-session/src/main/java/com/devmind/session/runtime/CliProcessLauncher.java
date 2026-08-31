package com.devmind.session.runtime;

import com.devmind.session.config.SessionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
 * claude 多为 {@code .cmd} 包装，需经 {@code cmd.exe /c} 启动；路径可在配置指定，否则 {@code where claude} 探测。</p>
 *
 * <p>⚠️ 2026-08-30 spike 实测（claude 2.1.250）：</p>
 * <ul>
 *   <li>stream-json 输出必须加 {@code --verbose}，否则报错退出；</li>
 *   <li>交互输入格式是完整 user message：{@code {"type":"user","message":{"role":"user","content":[{"type":"text","text":"..."}]}}}，
 *       而非 {@code {"type":"input",...}}（后者被静默忽略→零输出）；</li>
 *   <li>初始 prompt 作为第一条 user message 写入 stdin（不再作为位置参数）；stdin 保持打开供后续注入。</li>
 * </ul>
 */
@Component
public class CliProcessLauncher implements SessionExecutor {

    private static final Logger log = LoggerFactory.getLogger(CliProcessLauncher.class);

    private final SessionProperties props;
    private final ObjectMapper mapper;
    private volatile String resolvedPath;

    public CliProcessLauncher(SessionProperties props, ObjectMapper mapper) {
        this.props = props;
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
        pb.redirectErrorStream(false);
        Process process = pb.start();

        if (ctx.taskSpec() != null && !ctx.taskSpec().isBlank()) {
            writeUserMessage(process.getOutputStream(), ctx.taskSpec());
        }
        return process;
    }

    /** 初始 prompt 以 stream-json user message 形式写入 stdin。 */
    void writeUserMessage(OutputStream out, String text) throws IOException {
        Map<String, Object> content = Map.of("type", "text", "text", text);
        Map<String, Object> message = Map.of("role", "user", "content", List.of(content));
        Map<String, Object> line = Map.of("type", "user", "message", message);
        String json = mapper.writeValueAsString(line) + "\n";
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.flush();
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
                ? props.getPermissionMode() : ctx.permissionMode());
        if (ctx.model() != null && !ctx.model().isBlank()) {
            cmd.add("--model");
            cmd.add(ctx.model());
        }
        return cmd;
    }

    /** claude 路径：配置优先，空则 where claude 探测（结果缓存）。 */
    String resolvePath() {
        if (props.getClaudePath() != null && !props.getClaudePath().isBlank()) {
            return props.getClaudePath().strip();
        }
        if (resolvedPath != null) {
            return resolvedPath;
        }
        try {
            Process p = new ProcessBuilder("where", "claude").redirectErrorStream(true).start();
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
