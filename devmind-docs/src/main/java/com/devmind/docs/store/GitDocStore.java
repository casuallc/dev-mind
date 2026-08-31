package com.devmind.docs.store;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.docs.config.DocsProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * git 仓库实现（CAP-03 FR-05）：内容即 docs-repo 中的文件，保存=写文件+commit。
 * 首次使用自动 git init 并补齐本地 user 配置（零配置启动）。
 */
@Component
public class GitDocStore implements DocStore {

    private static final Logger log = LoggerFactory.getLogger(GitDocStore.class);

    private final DocsProperties props;

    public GitDocStore(DocsProperties props) {
        this.props = props;
    }

    @Override
    public String write(String relativePath, String content, String message) {
        Path root = ensureRepo();
        Path file = resolve(root, relativePath);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "写入文档文件失败: " + relativePath, e);
        }
        run(root, 30, "git", "add", "--", relativePath);
        if (!hasStaged(root)) {
            return headSha();
        }
        run(root, 60, "git", "commit", "-m", message);
        String sha = headSha();
        log.info("文档已提交: {} ({}): {}", relativePath, sha == null ? "" : sha.substring(0, Math.min(7, sha.length())), message);
        return sha;
    }

    @Override
    public String delete(String relativePath, String message) {
        Path root = ensureRepo();
        Result rm = run(root, 30, "git", "rm", "-f", "--ignore-unmatch", relativePath);
        if (rm.exit() == 0 && !hasStaged(root)) {
            return headSha();
        }
        run(root, 60, "git", "commit", "-m", message);
        return headSha();
    }

    @Override
    public String read(String relativePath) {
        Path root = ensureRepo();
        Path file = resolve(root, relativePath);
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "读取文档文件失败: " + relativePath, e);
        }
    }

    @Override
    public String headSha() {
        Path root = ensureRepo();
        Result r = run(root, 15, "git", "rev-parse", "HEAD");
        return r.exit() == 0 ? r.stdout().trim() : "";
    }

    @Override
    public String push() {
        Path root = ensureRepo();
        Result remote = run(root, 15, "git", "remote");
        if (remote.exit() != 0 || remote.stdout().isBlank()) {
            return "docs-repo 未配置 remote，跳过 push";
        }
        Result branch = run(root, 15, "git", "branch", "--show-current");
        String b = branch.exit() == 0 ? branch.stdout().trim() : "";
        Result r = run(root, 60, "git", "push", "origin", b.isBlank() ? "HEAD" : b);
        return r.exit() == 0
                ? "已推送到远端 " + (b.isBlank() ? "HEAD" : b)
                : "push 失败: " + firstLine(r.stderr());
    }

    private boolean hasStaged(Path root) {
        Result r = run(root, 15, "git", "diff", "--cached", "--quiet");
        return r.exit() != 0; // 0=无暂存变更
    }

    private Path ensureRepo() {
        String path = props.getRepoPath();
        if (path == null || path.isBlank()) {
            throw new DevMindException(ErrorCode.INTERNAL, "未配置 devmind.docs.repo-path（application-local.yml），文档存储不可用");
        }
        Path root = Path.of(path).toAbsolutePath().normalize();
        try {
            if (!Files.exists(root)) {
                Files.createDirectories(root);
            }
            if (!Files.exists(root.resolve(".git"))) {
                run(root, 60, "git", "init", "-b", "main");
            }
            ensureIdentity(root);
            return root;
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "初始化 docs-repo 失败: " + root, e);
        }
    }

    private void ensureIdentity(Path root) {
        if (run(root, 10, "git", "config", "user.name").stdout().isBlank()) {
            run(root, 10, "git", "config", "user.name", props.getAuthor());
        }
        if (run(root, 10, "git", "config", "user.email").stdout().isBlank()) {
            run(root, 10, "git", "config", "user.email", "devmind@local");
        }
    }

    /** 相对路径必须落在 repo 内（防 ../ 穿越）。 */
    private Path resolve(Path root, String relativePath) {
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "非法文档路径: " + relativePath);
        }
        return file;
    }

    private String firstLine(String s) {
        if (s == null) return "";
        return s.lines().findFirst().orElse("").trim();
    }

    private record Result(int exit, String stdout, String stderr) {}

    private Result run(Path cwd, int timeoutSec, String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(cwd.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new DevMindException(ErrorCode.INTERNAL, "git 命令超时: " + String.join(" ", args));
            }
            return new Result(p.exitValue(), out, err);
        } catch (IOException e) {
            throw new DevMindException(ErrorCode.INTERNAL, "git 命令执行失败: " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DevMindException(ErrorCode.INTERNAL, "git 命令被中断: " + String.join(" ", args));
        }
    }
}
