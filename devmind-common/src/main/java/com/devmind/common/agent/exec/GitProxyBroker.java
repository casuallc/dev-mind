package com.devmind.common.agent.exec;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAP-58 FR-06：会话内本地 git 凭据代理的登记簿（纯内存，runner 进程级）。
 *
 * <p>背景：会话里 agent（Claude Code Bash 工具）的 git 命令是 runner 主机本地进程执行，
 * 不过 WS 协议；而 launch 下发的 repo.token 按凭据红线只注入 runner 自己发起的 git 进程
 * 参数，worktree 的 origin 是裸 URL → agent 裸跑 git fetch/push 无凭据。代理方案：
 * worktree 的 origin 重写为 {@code http://127.0.0.1:<port>/git/<key><repo-path>}，
 * runner 内置回环代理按 key 查本登记簿取 (upstreamOrigin, token)，注入 Authorization
 * 转发真实远端。token 全程零落盘，agent「可用不可取」（能读到的只有代理 URL）。</p>
 *
 * <p>生命周期：prepare/prepareMulti 时 register（同 key 覆盖 = 刷新 token，同需求
 * 多会话共用工作树天然幂等）；收口/释放删 worktree 前按 key revoke；runner 重启全清
 * （token 本来也只随 launch 帧存活，重启后 resume 重新 launch 会重新登记）。</p>
 */
public class GitProxyBroker {

    /** 一条代理凭据：上游源（scheme://host[:port]，无路径）+ PAT。 */
    public record CredEntry(String upstreamOrigin, String token) {
    }

    private final String baseUrl; // http://127.0.0.1:<port>/git（无尾斜杠）
    private final ConcurrentHashMap<String, CredEntry> creds = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public GitProxyBroker(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.isBlank()) {
            throw new IllegalArgumentException("git 代理 baseUrl 为空");
        }
        this.baseUrl = b;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 新会话级随机 key（16 hex；agent 可见但只能用于本会话代理，取不到 PAT 本体）。 */
    public String newKey() {
        byte[] buf = new byte[8];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(16);
        for (byte x : buf) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /** 登记/刷新：key →（remoteUrl 的 origin，token）。同 key 覆盖（token 轮换/换 actor 幂等）。 */
    public void register(String key, String remoteUrl, String token) {
        if (key == null || key.isBlank() || token == null || token.isBlank()) {
            return;
        }
        creds.put(key, new CredEntry(originOf(remoteUrl), token));
    }

    public Optional<CredEntry> lookup(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(creds.get(key));
    }

    public void revoke(String key) {
        if (key != null) {
            creds.remove(key);
        }
    }

    /** 若 url 是本代理形态（baseUrl + "/" 前缀）取 key；否则返回 null（裸 origin / 他人代理）。 */
    public String extractKey(String url) {
        if (url == null) {
            return null;
        }
        String prefix = baseUrl + "/";
        if (!url.startsWith(prefix)) {
            return null;
        }
        String rest = url.substring(prefix.length());
        int slash = rest.indexOf('/');
        String key = slash < 0 ? rest : rest.substring(0, slash);
        return key.isBlank() ? null : key;
    }

    /** 代理 URL = baseUrl/key + remoteUrl 的路径段（查询串不带——git 远端 URL 不持有查询串）。 */
    public String proxyUrlFor(String key, String remoteUrl) {
        URI uri = URI.create(remoteUrl.trim());
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return baseUrl + "/" + key + path;
    }

    /** remoteUrl → scheme://host[:port]（代理转发时的上游拼接基座）。 */
    static String originOf(String remoteUrl) {
        URI uri = URI.create(remoteUrl.trim());
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() > 0) {
            sb.append(':').append(uri.getPort());
        }
        return sb.toString();
    }

    /** 当前登记条数（监控/测试用）。 */
    public int size() {
        return creds.size();
    }
}
