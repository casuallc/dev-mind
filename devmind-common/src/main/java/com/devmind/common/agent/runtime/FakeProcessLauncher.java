package com.devmind.common.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 假进程执行器：用内置 {@code fake-agent.js}（Node 脚本）模拟 claude stream-json 行为，
 * 便于在无 claude 环境或自测时走通"起/管/收 + 输入/授权"全链路。
 * 输出与 claude 同 schema，因此解析共用 {@link CliEventParser}。
 */
@Component
public class FakeProcessLauncher implements SessionExecutor {

    private static final Logger log = LoggerFactory.getLogger(FakeProcessLauncher.class);
    private static final String SCRIPT_RESOURCE = "/session/fake-agent.js";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private volatile Path scriptPath;

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public Process launch(LaunchContext ctx) throws IOException {
        Path script = extractScript();
        Path cwd = ctx.worktree() != null ? ctx.worktree() : Path.of("").toAbsolutePath();

        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        cmd.add(script.toString());
        cmd.add(ctx.sessionId());
        cmd.add("hold");
        log.info("启动 fake 会话: {}  cwd={}", ctx.sessionId(), cwd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        // 与 CliProcessLauncher 一致：初始 prompt 作首条 user message 写入 stdin（续接不重复下达）。
        // fake-agent 会回显 → 初始 prompt（含 CAP-46 库概览节等注入内容）在事件流可观测，E2E 可断言
        if ((ctx.resumeSessionId() == null || ctx.resumeSessionId().isBlank())
                && ctx.taskSpec() != null && !ctx.taskSpec().isBlank()) {
            OutputStream out = proc.getOutputStream();
            out.write((CliProcessLauncher.buildUserMessage(mapper, ctx.taskSpec(), List.of()) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        return proc;
    }

    private Path extractScript() throws IOException {
        if (scriptPath != null && Files.exists(scriptPath)) {
            return scriptPath;
        }
        try (InputStream in = getClass().getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("缺少内置脚本资源: " + SCRIPT_RESOURCE);
            }
            Path tmp = Files.createTempFile("fake-agent-", ".js");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            scriptPath = tmp;
            return tmp;
        }
    }
}
