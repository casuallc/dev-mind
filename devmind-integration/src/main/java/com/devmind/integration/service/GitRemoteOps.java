package com.devmind.integration.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * CAP-18 第 1 层 Git 远程操作（与平台无关）：push 分支 / push tag / clone 仓库（CAP-23）。
 * 凭据注入方式：HTTPS remote 在 URL 中内嵌 oauth2:&lt;token&gt;@，仅存在于进程参数，
 * 不落 .git/config 或 .git-credentials 明文；所有输出经脱敏（token → ***）后才允许外溢。
 * MVP 仅支持 http/https remote；ssh（git@）明确报错提示。
 */
@Service
public class GitRemoteOps {

    private static final Logger log = LoggerFactory.getLogger(GitRemoteOps.class);
    private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(5);
    /** CAP-23：克隆超时（大库场景，独立于 push 超时） */
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(30);

    public record GitResult(boolean ok, String output) {}

    /** 推送本地分支到绑定远程（同名远端分支） */
    public GitResult pushBranch(String repoPath, String branch, String remoteUrl, String token) {
        String url = withToken(remoteUrl, token);
        return exec(repoPath, token, List.of("push", url, branch + ":" + branch));
    }

    /** CAP-24 FR-02 凭证连通性自检：git ls-remote（只读，repoPath 用当前目录不参与鉴权） */
    public GitResult lsRemote(String remoteUrl, String token) {
        String url = withToken(remoteUrl, token);
        return exec(".", token, List.of("ls-remote", url, "HEAD"));
    }

    /**
     * CAP-26：fetch 指定 ref 到 FETCH_HEAD。token 非空走 withToken 显式 URL 注入
     * （不依赖 origin 凭据——CLONE 库的 origin 已被清成干净 URL）；token 空 = 匿名。
     * ref 空 = 不指定 refspec（fetch 全部）。
     */
    public GitResult fetch(String repoPath, String ref, String remoteUrl, String token) {
        String url = token == null || token.isBlank() ? remoteUrl.trim() : withToken(remoteUrl, token);
        List<String> args = new ArrayList<>(List.of("fetch", url));
        if (ref != null && !ref.isBlank()) {
            args.add(ref.trim());
        }
        return exec(repoPath, token, args);
    }

    /** 推送 tag 到绑定远程 */
    public GitResult pushTag(String repoPath, String tag, String remoteUrl, String token) {
        String url = withToken(remoteUrl, token);
        return exec(repoPath, token, List.of("push", url, "refs/tags/" + tag + ":refs/tags/" + tag));
    }

    /**
     * CAP-23：克隆远端仓库到 targetDir（targetDir 必须不存在或为空目录）。
     * token 非空时内嵌 URL（仅进程参数）；匿名（token 空）支持 http/https 与 file://。
     * 输出逐行流式回调（已脱敏）。
     *
     * <p><b>token 残留防护</b>：clone 会把带 token 的 URL 持久化进 .git/config 的
     * remote.origin.url（push 场景无此问题），成功后立即 set-url 回干净 URL；
     * 后续 fetch/push 仍走 withToken 显式注入，不依赖 origin 凭据。</p>
     */
    public GitResult cloneRepo(String remoteUrl, String token, String targetDir, String branch,
                               java.util.function.Consumer<String> lineSink) {
        String cleanUrl = remoteUrl == null ? "" : remoteUrl.trim();
        boolean anonymous = token == null || token.isBlank();
        String url;
        if (anonymous) {
            // 匿名克隆：公开仓库 http/https，或 file:// 本地验证通道
            if (!(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")
                    || cleanUrl.startsWith("file://"))) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "remote_url 协议仅支持 http/https（匿名另支持 file://）：" + cleanUrl);
            }
            url = cleanUrl;
        } else {
            url = withToken(cleanUrl, token);
        }
        List<String> cmd = new ArrayList<>(List.of("git", "clone", "--progress"));
        if (branch != null && !branch.isBlank()) {
            cmd.add("--branch");
            cmd.add(branch.trim());
        }
        cmd.add(url);
        cmd.add(targetDir);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // 流式读输出（clone --progress 走 stderr，redirectErrorStream 合并后逐行回调，已脱敏）
            Thread reader = Thread.startVirtualThread(() -> {
                try (var br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (lineSink != null) {
                            lineSink.accept(sanitize(line, token));
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被杀/流关闭：忽略，由 waitFor 结果兜底
                }
            });
            boolean finished = p.waitFor(CLONE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new GitResult(false, "git clone 超时（" + CLONE_TIMEOUT.toMinutes() + " 分钟）已终止");
            }
            reader.join(10_000);
            boolean ok = p.exitValue() == 0;
            if (ok && !anonymous) {
                // 清除 .git/config 中残留的带 token origin URL
                exec(targetDir, token, List.of("remote", "set-url", "origin", cleanUrl));
            }
            return new GitResult(ok, ok ? "克隆完成" : "git clone 失败，exit=" + p.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(false, "git clone 被中断");
        } catch (Exception e) {
            log.warn("git clone 执行失败: {}", e.getMessage());
            return new GitResult(false, "git clone 执行失败: " + e.getMessage());
        }
    }

    /** CAP-23：探测克隆后仓库的远端默认分支（origin/HEAD → 分支名；失败返回 ok=false）。 */
    public GitResult remoteHeadBranch(String repoPath) {
        GitResult r = exec(repoPath, null,
                List.of("symbolic-ref", "--short", "refs/remotes/origin/HEAD"));
        if (!r.ok()) {
            return r;
        }
        String out = r.output() == null ? "" : r.output().trim();
        // 形如 origin/main → main
        int slash = out.indexOf('/');
        String branch = slash >= 0 ? out.substring(slash + 1) : out;
        return new GitResult(!branch.isBlank(), branch);
    }

    /** HTTPS URL 内嵌 PAT（GitLab 约定 oauth2 用户名；GitHub 亦接受任意用户名 + PAT） */
    private String withToken(String remoteUrl, String token) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "仓库未配置 remote_url，无法推送（请在项目仓库设置中填写远端地址）");
        }
        String url = remoteUrl.trim();
        if (url.startsWith("git@") || url.startsWith("ssh://")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "MVP 仅支持 http/https 远端（PAT 注入），当前 remote_url 为 ssh 形式：" + url);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "remote_url 不是合法 URL：" + url);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "remote_url 协议仅支持 http/https：" + url);
        }
        String userInfo = "oauth2:" + token;
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(userInfo).append('@').append(uri.getHost());
        if (uri.getPort() > 0) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        return sb.toString();
    }

    private GitResult exec(String repoPath, String token, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(repoPath);
        cmd.addAll(args);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // 读输出与等待分离，防缓冲满死锁
            var outFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            boolean finished = p.waitFor(PUSH_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new GitResult(false, "git push 超时（" + PUSH_TIMEOUT.toMinutes() + " 分钟）已终止");
            }
            String out = sanitize(outFuture.join(), token);
            return new GitResult(p.exitValue() == 0, out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(false, "git push 被中断");
        } catch (Exception e) {
            log.warn("git 远程操作失败: {}", e.getMessage());
            return new GitResult(false, "git 执行失败: " + e.getMessage());
        }
    }

    /** 输出脱敏：token 与其 URL 编码形态都替换为 *** */
    private String sanitize(String s, String token) {
        if (s == null || token == null || token.isEmpty()) {
            return s;
        }
        String out = s.replace(token, "***");
        String encoded = java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (!encoded.equals(token)) {
            out = out.replace(encoded, "***");
        }
        return out;
    }
}
