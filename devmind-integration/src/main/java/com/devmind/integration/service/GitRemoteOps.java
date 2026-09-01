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
 * CAP-18 第 1 层 Git 远程操作（与平台无关）：push 分支 / push tag。
 * 凭据注入方式：HTTPS remote 在 URL 中内嵌 oauth2:&lt;token&gt;@，仅存在于进程参数，
 * 不落 .git/config 或 .git-credentials 明文；所有输出经脱敏（token → ***）后才允许外溢。
 * MVP 仅支持 http/https remote；ssh（git@）明确报错提示。
 */
@Service
public class GitRemoteOps {

    private static final Logger log = LoggerFactory.getLogger(GitRemoteOps.class);
    private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(5);

    public record GitResult(boolean ok, String output) {}

    /** 推送本地分支到绑定远程（同名远端分支） */
    public GitResult pushBranch(String repoPath, String branch, String remoteUrl, String token) {
        String url = withToken(remoteUrl, token);
        return exec(repoPath, token, List.of("push", url, branch + ":" + branch));
    }

    /** 推送 tag 到绑定远程 */
    public GitResult pushTag(String repoPath, String tag, String remoteUrl, String token) {
        String url = withToken(remoteUrl, token);
        return exec(repoPath, token, List.of("push", url, "refs/tags/" + tag + ":refs/tags/" + tag));
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
