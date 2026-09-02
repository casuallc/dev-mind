package com.devmind.agent.runner;

import com.devmind.session.model.SessionEvent;
import com.devmind.session.proc.ProcessHelper;
import com.devmind.session.runtime.CliEventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * runner 侧会话登记表：sessionId → 本地子进程。stdout/stderr 行经 {@link CliEventParser}
 * 解析成 {@link SessionEvent} 后上行（事件解析下沉 runner，服务端对 CLI schema 无感）；
 * 进程退出上行 exit 帧。
 */
public class RunnerSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunnerSessionRegistry.class);

    /** 上行事件帧出口（ServerConnection.send）。 */
    public interface FrameSender {
        void send(Map<String, Object> frame);
    }

    private final CliEventParser parser;
    private final FrameSender sender;
    private final Map<String, RunnerSession> sessions = new ConcurrentHashMap<>();

    public RunnerSessionRegistry(CliEventParser parser, FrameSender sender) {
        this.parser = parser;
        this.sender = sender;
    }

    public int size() {
        return sessions.size();
    }

    /** hello 对账：仍活着的会话 id 清单。 */
    public List<String> activeSessionIds() {
        return sessions.values().stream()
                .filter(s -> s.process.isAlive())
                .map(s -> s.sessionId)
                .toList();
    }

    public void register(String sessionId, Process process) {
        RunnerSession s = new RunnerSession(sessionId, process);
        sessions.put(sessionId, s);
        Thread.ofVirtual().name("runner-stdout-" + sessionId).start(() -> readLoop(s, false));
        Thread.ofVirtual().name("runner-stderr-" + sessionId).start(() -> readLoop(s, true));
    }

    /** 写一行 JSON 到会话 stdin（调用方已按 CLI 协议拼装）。 */
    public void writeStdin(String sessionId, String jsonLine) {
        RunnerSession s = sessions.get(sessionId);
        if (s == null || !s.process.isAlive()) {
            return;
        }
        synchronized (s.stdinLock) {
            try {
                OutputStream out = s.process.getOutputStream();
                out.write((jsonLine + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                log.warn("写 stdin 失败: session={} err={}", sessionId, e.getMessage());
            }
        }
    }

    /** 优雅结束：关 stdin（EOF），agent 自然退出。 */
    public void closeStdin(String sessionId) {
        RunnerSession s = sessions.get(sessionId);
        if (s == null) {
            return;
        }
        synchronized (s.stdinLock) {
            try {
                s.process.getOutputStream().close();
            } catch (IOException e) {
                // 忽略
            }
        }
    }

    public void kill(String sessionId) {
        RunnerSession s = sessions.get(sessionId);
        if (s != null) {
            ProcessHelper.killTree(s.process);
        }
    }

    /** runner 关闭前清理全部子进程。 */
    public void killAll() {
        sessions.keySet().forEach(this::kill);
    }

    private void readLoop(RunnerSession s, boolean stderr) {
        String source = stderr ? "stderr" : "stdout";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                stderr ? s.process.getErrorStream() : s.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                SessionEvent ev = parser.parse(s.seq.incrementAndGet(), line, source);
                if (ev != null) {
                    sendEvent(s.sessionId, ev);
                }
            }
        } catch (IOException e) {
            log.debug("读取结束/中断: session={} source={} err={}", s.sessionId, source, e.getMessage());
        } finally {
            if (!stderr) {
                onProcessEnd(s);
            }
        }
    }

    /** 进程退出收口（stdout EOF 后取退出码上行 exit 帧）。 */
    private void onProcessEnd(RunnerSession s) {
        int code;
        try {
            code = s.process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        sessions.remove(s.sessionId, s);
        sender.send(Map.of("type", "exit", "sessionId", s.sessionId, "code", code));
        log.info("会话进程退出: session={} exit={}", s.sessionId, code);
    }

    private void sendEvent(String sessionId, SessionEvent ev) {
        Map<String, Object> frame = new java.util.LinkedHashMap<>();
        frame.put("type", "event");
        frame.put("sessionId", sessionId);
        frame.put("eventType", ev.type());
        frame.put("content", ev.content());
        frame.put("source", ev.source());
        frame.put("timestamp", ev.timestamp());
        frame.put("payload", ev.payload());
        sender.send(frame);
    }

    private static final class RunnerSession {
        final String sessionId;
        final Process process;
        final AtomicLong seq = new AtomicLong();
        final Object stdinLock = new Object();

        RunnerSession(String sessionId, Process process) {
            this.sessionId = sessionId;
            this.process = process;
        }
    }
}
