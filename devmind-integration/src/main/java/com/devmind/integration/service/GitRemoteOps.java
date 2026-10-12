package com.devmind.integration.service;

import com.devmind.common.egress.EgressProxyRouter;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CAP-18 第 1 层 Git 远程操作（与平台无关）：push 分支 / push tag / clone 仓库（CAP-23）。
 * 凭据注入方式：HTTPS remote 在 URL 中内嵌 oauth2:&lt;token&gt;@，仅存在于进程参数，
 * 不落 .git/config 或 .git-credentials 明文；所有输出经脱敏（token → ***）后才允许外溢。
 * MVP 仅支持 http/https remote；ssh（git@）明确报错提示。
 *
 * <p>CAP-70 FR-05：网络类操作（push/fetch/ls-remote/clone）组命令时按 remote host 查
 * {@link EgressProxyRouter}，命中出口规则即注入 {@code -c http.<scheme://host[:port]>.proxy=
 * socks5h://127.0.0.1:<port>}（per-URL、命令级生命周期，不落 repo 配置）；命中但不可用
 * 抛 CONFLICT 快速失败（禁静默回落直连）；未命中/无 router（agent 模块缺席）零行为变化。</p>
 */
@Service
public class GitRemoteOps {

    private static final Logger log = LoggerFactory.getLogger(GitRemoteOps.class);
    private static final Duration PUSH_TIMEOUT = Duration.ofMinutes(5);
    /** CAP-23：克隆超时（大库场景，独立于 push 超时） */
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(30);

    /** CAP-70：出口路由器（agent 模块实现，ObjectProvider 探测防模块缺席/循环依赖） */
    private final ObjectProvider<EgressProxyRouter> egressRouterProvider;

    public GitRemoteOps(ObjectProvider<EgressProxyRouter> egressRouterProvider) {
        this.egressRouterProvider = egressRouterProvider;
    }

    public record GitResult(boolean ok, String output) {}

    /** CAP-74：批量 log 单路径命中结果（committedAt=最近一次提交，firstCommittedAt=窗口内最旧一次，作 createdAt） */
    public record PathCommit(String path, String sha, java.time.Instant committedAt,
                             java.time.Instant firstCommittedAt, String subject,
                             java.util.Map<String, String> trailers) {}

    /** 推送本地分支到绑定远程（同名远端分支）。token 空 = 匿名（含 file:// 本地验证通道） */
    public GitResult pushBranch(String repoPath, String branch, String remoteUrl, String token) {
        String url = token == null || token.isBlank() ? anonymousUrl(remoteUrl) : withToken(remoteUrl, token);
        return exec(repoPath, token, remoteUrl, List.of("push", url, branch + ":" + branch));
    }

    /** CAP-74：本地分支与远端分支不同名的 refspec 推送（服务端仓本地恒 main，远端分支可配） */
    public GitResult pushBranch(String repoPath, String localBranch, String remoteBranch,
                                String remoteUrl, String token) {
        String url = token == null || token.isBlank() ? anonymousUrl(remoteUrl) : withToken(remoteUrl, token);
        return exec(repoPath, token, remoteUrl, List.of("push", url, localBranch + ":" + remoteBranch));
    }

    /** 匿名 push 的 URL 校验（http/https/file://；ssh 明确报错） */
    private String anonymousUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "仓库未配置 remote_url，无法推送（请在设置中绑定远端地址）");
        }
        String url = remoteUrl.trim();
        if (url.startsWith("git@") || url.startsWith("ssh://")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "MVP 仅支持 http/https 远端（PAT 注入），当前 remote_url 为 ssh 形式：" + url);
        }
        if (!(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://"))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "remote_url 协议仅支持 http/https/file://（匿名）：" + url);
        }
        return url;
    }

    /** CAP-74：初始化本地仓（目录不存在则创建；已有 .git 幂等返回 ok） */
    public GitResult initRepo(String repoPath, String defaultBranch) {
        java.nio.file.Path dir = java.nio.file.Path.of(repoPath);
        if (java.nio.file.Files.exists(dir.resolve(".git"))) {
            return new GitResult(true, "仓库已存在，跳过 init");
        }
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (IOException e) {
            return new GitResult(false, "创建仓库目录失败: " + e.getMessage());
        }
        String branch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch.trim();
        return exec(repoPath, null, List.of("init", "-b", branch));
    }

    /**
     * CAP-74：stage 指定路径并提交（单写者场景，pathspec 相对 repoPath）。
     * message 经 {@code -F messageFile} 读取（UTF-8 文件，Windows 中文/引号不走 argv 防乱码）；
     * 署名经 {@code -c user.name/user.email}（committer）+ {@code --author}（author）命令级注入，
     * 不改 .git/config；authorDate 非空经 env GIT_AUTHOR_DATE/GIT_COMMITTER_DATE 回填（迁移用）。
     * 无可提交变更（暂存为空）返回 ok(true) 且 output 含 "nothing to commit"，调用方视为幂等跳过。
     */
    public GitResult addCommit(String repoPath, List<String> paths, java.nio.file.Path messageFile,
                               String authorName, String authorEmail, java.time.Instant authorDate) {
        if (paths == null || paths.isEmpty()) {
            return new GitResult(false, "paths 为空");
        }
        if (authorName == null || authorName.isBlank() || authorEmail == null || authorEmail.isBlank()) {
            throw new IllegalArgumentException("authorName/authorEmail 必填（调用方负责回退兜底）");
        }
        List<String> addArgs = new ArrayList<>(List.of("add", "--"));
        addArgs.addAll(paths);
        GitResult add = exec(repoPath, null, addArgs);
        if (!add.ok()) {
            return add;
        }
        List<String> diffArgs = new ArrayList<>(List.of("diff", "--cached", "--quiet", "--"));
        diffArgs.addAll(paths);
        if (exec(repoPath, null, diffArgs).ok()) {
            return new GitResult(true, "nothing to commit");
        }
        List<String> commitArgs = new ArrayList<>(List.of(
                "-c", "user.name=" + authorName, "-c", "user.email=" + authorEmail,
                "commit", "-F", messageFile.toAbsolutePath().toString(),
                "--author=" + authorName + " <" + authorEmail + ">"));
        java.util.Map<String, String> env = null;
        if (authorDate != null) {
            env = java.util.Map.of("GIT_AUTHOR_DATE", authorDate.toString(),
                    "GIT_COMMITTER_DATE", authorDate.toString());
        }
        return exec(repoPath, null, null, commitArgs, env);
    }

    /**
     * CAP-74 M2：merge --no-edit（写前 fetch 后收敛远端改动）。merge 可能产生合并提交，
     * 命令级注入兜底身份防无全局 git 配置报错；冲突返回 ok(false)，output 含 CONFLICT 行，
     * 调用方负责 {@link #mergeAbort} 清理 MERGING 状态。
     */
    public GitResult mergeNoEdit(String repoPath, String ref) {
        return exec(repoPath, null, List.of(
                "-c", "user.name=DevMind", "-c", "user.email=devmind@local",
                "merge", "--no-edit", ref));
    }

    /** CAP-74 M2：冲突后中止合并（仓不留 MERGING 状态） */
    public GitResult mergeAbort(String repoPath) {
        return exec(repoPath, null, List.of("merge", "--abort"));
    }

    /**
     * CAP-74：一次调用批量取多路径各自的提交信息（禁 per-file fork）。
     * 命令：{@code git -c i18n.logOutputEncoding=UTF-8 log --encoding=UTF-8
     * --format=%x1e%H%x1f%aI%x1f%s%x1f%b%x1e --name-only -n <cap> -- <paths...>}
     * （%x1f/%x1e 分隔符 + UTF-8 编码强制，GitLogScanner 先例防 Windows GBK 乱码）。
     * 按时间倒序：每路径首次命中 = 最近一次提交；窗口内末次命中 = firstCommittedAt（作 createdAt，
     * cap 截断时退化为最近次，可接受）。无提交的路径不出现在结果中。
     */
    public List<PathCommit> lastCommitByPath(String repoPath, List<String> paths, int maxCommits) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, maxCommits);
        List<String> args = new ArrayList<>(List.of(
                "-c", "i18n.logOutputEncoding=UTF-8",
                "log", "--encoding=UTF-8",
                "--format=%x1e%H%x1f%aI%x1f%s%x1f%b%x1e", "--name-only",
                "-n", String.valueOf(cap), "--"));
        args.addAll(paths);
        GitResult r = exec(repoPath, null, args);
        if (!r.ok() || r.output() == null || r.output().isBlank()) {
            return List.of();
        }
        java.util.Set<String> wanted = new java.util.HashSet<>(paths);
        // split 后段交替：[前缀空, 字段, 文件名, 字段, 文件名, ...]
        String[] segs = r.output().split("\u001e", -1);
        var latest = new java.util.LinkedHashMap<String, PathCommit>();
        var oldestAt = new java.util.HashMap<String, java.time.Instant>();
        for (int i = 1; i < segs.length; i += 2) {
            // 注意：禁 strip()——\u001f ≤ U+0020 会被 strip 吃掉，空 body 时尾部分隔符丢失导致字段数不足
            String fields = trimWs(segs[i]);
            if (fields.isEmpty()) {
                continue;
            }
            String[] f = fields.split("\u001f", 4);
            if (f.length < 4) {
                continue;
            }
            java.time.Instant at;
            try {
                at = java.time.Instant.parse(f[1].strip());
            } catch (Exception e) {
                continue;
            }
            String names = i + 1 < segs.length ? segs[i + 1] : "";
            for (String line : names.split("\n")) {
                String p = line.strip();
                if (p.isEmpty() || !wanted.contains(p)) {
                    continue;
                }
                latest.putIfAbsent(p, new PathCommit(p, f[0].strip(), at, at,
                        f[2].strip(), parseTrailers(f[3])));
                oldestAt.put(p, at);
            }
        }
        List<PathCommit> out = new ArrayList<>();
        for (var e : latest.entrySet()) {
            PathCommit pc = e.getValue();
            out.add(new PathCommit(pc.path(), pc.sha(), pc.committedAt(),
                    oldestAt.getOrDefault(e.getKey(), pc.committedAt()), pc.subject(), pc.trailers()));
        }
        return out;
    }

    /** 仅去首尾空白（空格/制表/换行），保留 \u001f/\u001e 控制符（strip 会误吃） */
    private static String trimWs(String s) {
        int b = 0, e = s.length();
        while (b < e && (s.charAt(b) == ' ' || s.charAt(b) == '\t' || s.charAt(b) == '\n' || s.charAt(b) == '\r')) {
            b++;
        }
        while (e > b && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == '\t' || s.charAt(e - 1) == '\n' || s.charAt(e - 1) == '\r')) {
            e--;
        }
        return s.substring(b, e);
    }

    /** commit body 提取 trailer（Key: Value 行，同名后写覆盖） */
    private static java.util.Map<String, String> parseTrailers(String body) {
        var map = new java.util.LinkedHashMap<String, String>();
        if (body == null) {
            return map;
        }
        for (String line : body.split("\n")) {
            String l = line.strip();
            int colon = l.indexOf(':');
            if (colon > 0 && l.substring(0, colon).matches("[A-Za-z0-9-]+")) {
                map.put(l.substring(0, colon), l.substring(colon + 1).strip());
            }
        }
        return map;
    }

    /** CAP-24 FR-02 凭证连通性自检：git ls-remote（只读，repoPath 用当前目录不参与鉴权） */
    public GitResult lsRemote(String remoteUrl, String token) {
        String url = withToken(remoteUrl, token);
        return exec(".", token, remoteUrl, List.of("ls-remote", url, "HEAD"));
    }

    /**
     * CAP-73：ls-remote 指定 ref（远端需求分支 HEAD 探测）。输出形如
     * {@code <sha>\trefs/heads/<branch>}；ref 不存在 = ok(true) 且 output 为空
     * （git ls-remote 对缺失 ref 退出码仍为 0，调用方按 output 判空）。
     */
    public GitResult lsRemote(String remoteUrl, String token, String ref) {
        String url = token == null || token.isBlank() ? anonymousUrl(remoteUrl) : withToken(remoteUrl, token);
        return exec(".", token, remoteUrl, List.of("ls-remote", url, ref));
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
        return exec(repoPath, token, remoteUrl, args);
    }

    /** 推送 tag 到绑定远程 */
    public GitResult pushTag(String repoPath, String tag, String remoteUrl, String token) {
        String url = withToken(remoteUrl, token);
        return exec(repoPath, token, remoteUrl, List.of("push", url, "refs/tags/" + tag + ":refs/tags/" + tag));
    }

    /**
     * CAP-29：全量抓取远端分支（显式 refspec 映射 + --prune 清理已删分支）。
     * token 走 withToken 显式 URL 注入，不依赖 origin 凭据；token 空 = 匿名（含 file://）。
     */
    public GitResult fetchAllRefs(String repoPath, String remoteUrl, String token) {
        String url = token == null || token.isBlank() ? remoteUrl.trim() : withToken(remoteUrl, token);
        return exec(repoPath, token, remoteUrl,
                List.of("fetch", url, "+refs/heads/*:refs/remotes/origin/*", "--prune"));
    }

    /** CAP-29：列出远程跟踪分支（refs/remotes/origin/* 短名，含 origin/ 前缀与 origin/HEAD 指针，调用方过滤）。 */
    public GitResult listRemoteBranches(String repoPath) {
        return exec(repoPath, null,
                List.of("for-each-ref", "--format=%(refname:short)", "refs/remotes/origin"));
    }

    /**
     * CAP-29：fetch 后把已检出的默认分支快进到 origin/&lt;branch&gt;（纯本地操作，无网络/凭据）。
     * 服务端克隆是只读用途的非裸库，本地分支不随 fetch 移动；失败（分叉等）由调用方忽略。
     */
    public GitResult ffOnly(String repoPath, String upstreamRef) {
        return exec(repoPath, null, List.of("merge", "--ff-only", upstreamRef));
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
        // CAP-70 FR-05：-c 出口代理注入必须插在子命令（clone）之前
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(egressProxyArgs(cleanUrl));
        cmd.addAll(List.of("clone", "--progress"));
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
        return exec(repoPath, token, null, args);
    }

    /**
     * @param cleanUrl 干净 remote URL（无 token），用于 CAP-70 出口规则查询；null = 纯本地操作不查
     */
    private GitResult exec(String repoPath, String token, String cleanUrl, List<String> args) {
        return exec(repoPath, token, cleanUrl, args, null);
    }

    /**
     * @param cleanUrl 干净 remote URL（无 token），用于 CAP-70 出口规则查询；null = 纯本地操作不查
     * @param env 额外环境变量（如 GIT_AUTHOR_DATE/GIT_COMMITTER_DATE 回填）；null = 不加
     */
    private GitResult exec(String repoPath, String token, String cleanUrl, List<String> args,
                           java.util.Map<String, String> env) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(egressProxyArgs(cleanUrl));
        cmd.add("-C");
        cmd.add(repoPath);
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (env != null) {
                pb.environment().putAll(env);
            }
            Process p = pb.start();
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

    /**
     * CAP-70 FR-05：出口代理注入参数。cleanUrl（无 token）命中出口规则 →
     * {@code [-c, http.<scheme://host[:port]>.proxy=socks5h://127.0.0.1:<port>]}；
     * 未命中/cleanUrl 空/router 缺席 → 空表（零行为变化）。
     * 命中但不可用（规则禁用/节点离线/协议&lt;v21/隧道断）由 router 抛 CONFLICT 快速失败。
     * per-URL 配置键从 cleanUrl 解析（不含 userinfo/path），防 token 泄进配置键
     * （-c 本就命令级不落盘，仍按干净 URL 取 host）。
     */
    List<String> egressProxyArgs(String cleanUrl) {
        if (cleanUrl == null || cleanUrl.isBlank()) {
            return List.of();
        }
        EgressProxyRouter router = egressRouterProvider.getIfAvailable();
        if (router == null) {
            return List.of();
        }
        Optional<String> proxyUrl = router.gitProxyUrl(cleanUrl);
        if (proxyUrl.isEmpty()) {
            return List.of();
        }
        URI uri;
        try {
            uri = URI.create(cleanUrl.trim());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        String authority = uri.getHost() == null ? null
                : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        if (uri.getScheme() == null || authority == null) {
            return List.of();
        }
        String key = "http." + uri.getScheme() + "://" + authority + ".proxy";
        return List.of("-c", key + "=" + proxyUrl.get());
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
