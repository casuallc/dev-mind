package com.devmind.session.runtime;

import com.devmind.session.config.SessionProperties;
import com.devmind.session.model.SessionEvent;
import com.devmind.session.proc.ProcessHelper;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地会话运行时：持有本机子进程，3 路虚拟线程（stdout 读 / stderr 读 / 事件落库由全局 saver 负责）。
 * 状态机与事件流语义在 {@link AbstractSessionRuntime}；本类只负责管道 IO 与 CLI 协议帧拼装。
 *
 * <p>stdout 必须始终被消费（否则 pipe 写满导致子进程阻塞）。</p>
 */
public class SessionRuntime extends AbstractSessionRuntime {

    private static final Logger log = LoggerFactory.getLogger(SessionRuntime.class);

    private final Process process;
    private final BufferedWriter stdin;
    private final ObjectMapper mapper;
    private final CliEventParser parser;
    private final Object stdinLock = new Object();

    public SessionRuntime(String id, Process process, ObjectMapper mapper, CliEventParser parser,
                          SessionEventSaver saver, RuntimeListener listener, SessionProperties props) {
        super(id, saver, listener, props);
        this.process = process;
        this.mapper = mapper;
        this.parser = parser;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    public long pid() { return process.pid(); }

    /** 启动 stdout/stderr 读取线程。 */
    public void start() {
        Thread.ofVirtual().name("stdout-" + id).start(this::stdoutLoop);
        Thread.ofVirtual().name("stderr-" + id).start(this::stderrLoop);
    }

    private void stdoutLoop() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                for (SessionEvent ev : parser.parse(this::nextSeq, line, "stdout")) {
                    publish(ev);
                }
            }
        } catch (IOException e) {
            log.debug("stdout 读取结束/中断: session={} err={}", id, e.getMessage());
        } finally {
            int code;
            try {
                code = process.exitValue();
            } catch (Exception e) {
                code = -1;
            }
            handleExit(code);
        }
    }

    private void stderrLoop() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                for (SessionEvent ev : parser.parse(this::nextSeq, line, "stderr")) {
                    publish(ev);
                }
            }
        } catch (IOException e) {
            log.debug("stderr 读取结束/中断: session={} err={}", id, e.getMessage());
        }
    }

    // ---------------- 平台差异钩子（本地管道 IO） ----------------

    /** 格式必须与 claude stream-json 一致：完整 user message，而非 {"type":"input"}。 */
    @Override
    protected void sendUserMessage(String text) {
        Map<String, Object> content = Map.of("type", "text", "text", text);
        Map<String, Object> message = Map.of("role", "user", "content", List.of(content));
        writeLine(toJson(Map.of("type", "user", "message", message)));
    }

    @Override
    protected void sendPermissionResult(String requestId, boolean accepted, String scope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "permission_result");
        payload.put("permission_request_id", requestId);
        payload.put("permission", accepted ? "allow" : "deny");
        if (accepted && scope != null && !scope.isBlank()) {
            payload.put("scope", scope);
        }
        writeLine(toJson(payload));
    }

    @Override
    protected void doFinish() {
        cleanupStdin();
    }

    @Override
    protected void destroyTree() {
        ProcessHelper.killTree(process);
    }

    @Override
    protected boolean alive() {
        return process.isAlive();
    }

    @Override
    protected void cleanupStdin() {
        synchronized (stdinLock) {
            try {
                stdin.close();
            } catch (IOException e) {
                // 忽略
            }
        }
    }

    private void writeLine(String json) {
        synchronized (stdinLock) {
            try {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            } catch (IOException e) {
                log.warn("写入 stdin 失败: session={} err={}", id, e.getMessage());
            }
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
