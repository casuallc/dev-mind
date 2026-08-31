package com.devmind.serveradapter.ssh;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.serveradapter.spi.ConnectResult;
import com.devmind.serveradapter.spi.ExecResult;
import com.devmind.serveradapter.spi.HealthCheckConfig;
import com.devmind.serveradapter.spi.HealthResult;
import com.devmind.serveradapter.spi.ServerAdapter;
import com.devmind.serveradapter.spi.ServerTarget;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * CAP-07 FR-03 SSH 实现（Apache MINA SSHD）。
 * 认证：password / privateKey（PEM 写临时文件经 FileKeyPairProvider 加载）。
 * 执行：脚本经 stdin 以 {@code sh -s} 方式运行（多行脚本无需落地远端文件）。
 * 文件传输：SFTP。
 * 安全说明：默认不做 host key 校验（AcceptAll），MVP 阶段接受；后续可加 known_hosts 白名单。
 */
@Component
public class SshServerAdapter implements ServerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SshServerAdapter.class);
    private static final long AUTH_VERIFY_MS = 10_000;

    @Override
    public String supportedType() {
        return "ssh";
    }

    @Override
    public ConnectResult connectTest(ServerTarget target, long timeoutMs) {
        long start = System.currentTimeMillis();
        String msg;
        try (ClientSession session = openSession(target, timeoutMs)) {
            msg = "SSH 连接成功: " + target.str("host", "?") + ":" + target.intVal("port", 22)
                    + " 用户 " + target.str("username", "?");
            return new ConnectResult(true, msg, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new ConnectResult(false, "连接失败: " + rootMessage(e), System.currentTimeMillis() - start);
        }
    }

    @Override
    public ExecResult execute(ServerTarget target, String script, long timeoutMs) {
        long start = System.currentTimeMillis();
        try (ClientSession session = openSession(target, timeoutMs)) {
            return runScript(session, script, timeoutMs, start);
        } catch (Exception e) {
            return new ExecResult(-1, false, "", rootMessage(e), System.currentTimeMillis() - start);
        }
    }

    @Override
    public void upload(ServerTarget target, String localPath, String remotePath, long timeoutMs) {
        try (ClientSession session = openSession(target, timeoutMs);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            try (var in = Files.newInputStream(Path.of(localPath))) {
                sftp.put(in, remotePath);
            }
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "SFTP 上传失败: " + rootMessage(e));
        }
    }

    @Override
    public String download(ServerTarget target, String remotePath, long timeoutMs) {
        try (ClientSession session = openSession(target, timeoutMs);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            try (var out = new ByteArrayOutputStream();
                 var handle = sftp.open(remotePath, SftpClient.OpenMode.Read)) {
                byte[] buf = new byte[8192];
                long offset = 0;
                int n;
                while ((n = sftp.read(handle, offset, buf, 0, buf.length)) > 0) {
                    out.write(buf, 0, n);
                    offset += n;
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "SFTP 下载失败: " + rootMessage(e));
        }
    }

    @Override
    public HealthResult healthCheck(ServerTarget target, HealthCheckConfig cfg, long timeoutMs) {
        long start = System.currentTimeMillis();
        String command = cfg != null ? cfg.command() : null;
        if (command == null || command.isBlank()) {
            return new HealthResult(false, "SSH 健康检查需提供 command", 0);
        }
        ExecResult r = execute(target, command, timeoutMs);
        return new HealthResult(r.success(), r.success() ? "健康检查通过" : "健康检查失败: " + r.stderr().trim(), r.durationMs());
    }

    // ---------- 内部 ----------

    private ClientSession openSession(ServerTarget target, long timeoutMs) throws Exception {
        String host = target.str("host", "");
        int port = target.intVal("port", 22);
        String username = target.str("username", "");
        if (host.isBlank() || username.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "SSH 配置缺 host/username");
        }
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        ClientSession session = client.connect(username, host, port)
                .verify(Duration.ofMillis(Math.min(timeoutMs, AUTH_VERIFY_MS)))
                .getSession();
        String authType = target.str("authType", "password");
        if ("key".equalsIgnoreCase(authType)) {
            addKeyIdentity(session, target);
        } else {
            session.addPasswordIdentity(target.str("password", ""));
        }
        session.auth().verify(Duration.ofMillis(Math.min(timeoutMs, AUTH_VERIFY_MS)));
        return session;
    }

    private void addKeyIdentity(ClientSession session, ServerTarget target) throws Exception {
        String pem = target.str("privateKey", "");
        if (pem.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "SSH key 认证缺少 privateKey");
        }
        Path tmp = Files.createTempFile("devmind-key-", ".pem");
        Files.writeString(tmp, pem);
        try {
            FileKeyPairProvider kpp = new FileKeyPairProvider(tmp);
            var keys = kpp.loadKeys(session);
            for (var kp : keys) {
                session.addPublicKeyIdentity(kp);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private ExecResult runScript(ClientSession session, String script, long timeoutMs, long start) throws IOException {
        ClientChannel channel = session.createExecChannel("sh -s");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        channel.setOut(out);
        channel.setErr(err);
        channel.open().verify(Duration.ofMillis(timeoutMs));
        if (script != null && !script.isEmpty()) {
            channel.getInvertedIn().write(script.getBytes(StandardCharsets.UTF_8));
        }
        channel.getInvertedIn().close();
        Set<ClientChannelEvent> events = channel.waitFor(Set.of(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS), timeoutMs);
        if (!events.contains(ClientChannelEvent.EXIT_STATUS) && !events.contains(ClientChannelEvent.CLOSED)) {
            channel.close();
            return new ExecResult(-1, false, out.toString(StandardCharsets.UTF_8), "执行超时", System.currentTimeMillis() - start);
        }
        Integer status = channel.getExitStatus();
        int exit = status == null ? -1 : status;
        channel.close();
        return new ExecResult(exit, exit == 0,
                out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
                System.currentTimeMillis() - start);
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
