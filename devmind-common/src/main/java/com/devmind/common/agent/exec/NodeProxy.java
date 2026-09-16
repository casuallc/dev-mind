package com.devmind.common.agent.exec;

import java.util.Set;

/**
 * CAP-43：runner 进程级节点外网代理 holder。权威源在服务端（agent_nodes.proxy_url/scopes），
 * 随 launch/worklog_push/exec/workspace_finalize 帧携带下发（协议 v8+），
 * {@code AgentRunnerMain} 中央 dispatch 收到即刷新本 holder（帧无 proxy 字段不动）。
 *
 * <p>三个 scope 的消费点：
 * <ul>
 *   <li>{@code git}——{@link RunnerWorkspace} 全部 git 网络操作（组命令时 {@code git} 后插
 *   {@code -c http.proxy=<url>}，进程级、不落 repo 配置）；</li>
 *   <li>{@code claude}——claude 子进程 env 注入 HTTP_PROXY/HTTPS_PROXY（大小写四件）；</li>
 *   <li>{@code exec}——exec 脚本进程 env 注入（帧 env 已有同名键不覆盖）。</li>
 * </ul>
 *
 * <p>URL 不带 userinfo（服务端入库前已拒绝）；scopes 为服务端归一化后的白名单子集
 * （git/claude/exec，服务端保证配了代理时非空）。volatile 单字段引用保证读写线程安全
 * （WS listener 写、session/exec/GC 线程读）。</p>
 */
public record NodeProxy(String url, Set<String> scopes) {

    private static volatile NodeProxy current;

    /** 帧携带 proxy 对象时刷新 holder（url 为空 = 显式清空） */
    public static void set(String url, Set<String> scopes) {
        current = (url == null || url.isBlank()) ? null : new NodeProxy(url, Set.copyOf(scopes));
    }

    public static void clear() {
        current = null;
    }

    public static NodeProxy get() {
        return current;
    }

    /** 当前代理配置是否适用于指定 scope（"git"/"claude"/"exec"） */
    public static boolean appliesTo(String scope) {
        NodeProxy p = current;
        return p != null && p.scopes().contains(scope);
    }

    /** 命中指定 scope 时代理 URL，未命中/未配置 → null（消费点免二次判空） */
    public static String urlFor(String scope) {
        NodeProxy p = current;
        return (p != null && p.scopes().contains(scope)) ? p.url() : null;
    }
}
