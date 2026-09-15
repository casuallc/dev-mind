// CAP-07 验证用嵌入式 SSH 服务器（MINA SSHD 2.16）。
// 用法: java -cp <sshd-core>:<sshd-common>:<sshd-sftp>:<slf4j-api> TestSshServer [port] [user] [password]
// 命令通道：适配器以 "sh -s" 执行，服务端读取 stdin 为脚本 → 写临时 .sh → bash 运行并回传输出。
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TestSshServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 2222;
        String user = args.length > 1 ? args[1] : "test";
        String password = args.length > 2 ? args[2] : "testpw";

        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(port);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        server.setPasswordAuthenticator((u, p, s) -> user.equals(u) && password.equals(p));
        server.setCommandFactory((ChannelSession channel, String command) -> new ScriptCommand());
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.start();

        System.out.println("READY port=" + port + " user=" + user + " password=" + password);
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }

    /** 读取 stdin 为脚本，写临时 .sh，bash 执行，回传 stdout/stderr 与退出码 */
    static class ScriptCommand implements Command {
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback cb;

        @Override
        public void setInputStream(InputStream in) { this.in = in; }
        @Override
        public void setOutputStream(OutputStream out) { this.out = out; }
        @Override
        public void setErrorStream(OutputStream err) { this.err = err; }
        @Override
        public void setExitCallback(ExitCallback cb) { this.cb = cb; }

        @Override
        public void start(ChannelSession channel, Environment env) {
            Thread t = new Thread(() -> {
                int code = 1;
                try {
                    String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Path tmp = Files.createTempFile("devmind-ssh-", ".sh");
                    Files.writeString(tmp, script, StandardCharsets.UTF_8);
                    Process p = new ProcessBuilder("bash", tmp.toAbsolutePath().toString()).start();
                    p.getOutputStream().close();
                    p.getInputStream().transferTo(out);
                    p.getErrorStream().transferTo(err);
                    code = p.waitFor();
                    try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
                } catch (Exception e) {
                    try { err.write(("TestSshServer: " + e).getBytes(StandardCharsets.UTF_8)); err.flush(); } catch (Exception ignored) { }
                    code = 1;
                }
                try { cb.onExit(code, code == 0 ? "ok" : "failed", false); } catch (Exception ignored) { }
            });
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void destroy(ChannelSession channel) { }
    }
}
