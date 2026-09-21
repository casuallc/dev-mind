package com.devmind.common.agent.runtime;

/**
 * CAP-30：运行时内核参数（不可变值对象）。替代原 SessionProperties 的内核子集——
 * Spring 配置入口仍留在各能力模块（SessionProperties/ChatProperties），经
 * {@code toRuntimeSettings()} 转换注入；runner（无 Spring）用 {@link #defaults()} + wither。
 *
 * @param ringBuffer             内存回放缓冲条数
 * @param idleTimeoutSec         WAITING_INPUT 空闲超时（秒），0=不自动结束
 * @param maxEventBytes          单条事件内容截断字符数，防前端卡死
 * @param defaultPermissionMode  调用方未指定权限模式时的默认（acceptEdits/plan/...）
 * @param claudePath             claude 可执行文件路径；空 = 按平台探测（where/which）
 * @param includePartialMessages CAP-50：给 claude 加 {@code --include-partial-messages}，
 *                               令其逐 token 吐 {@code stream_event} 增量。仅 runner 用得上
 *                               （服务端已无 CLI 执行路径）；旧版 claude 不认此参数会让进程
 *                               非零退出、会话直接 FAILED，故留了关掉的余地
 */
public record RuntimeSettings(int ringBuffer, long idleTimeoutSec, int maxEventBytes,
                              String defaultPermissionMode, String claudePath,
                              boolean includePartialMessages) {

    /**
     * 5 参便捷构造：CAP-50 的 partial messages 默认开启。服务端各能力模块只关心前五项
     * （它们都不拉 CLI 进程），走这个构造器即可不必感知新参数。
     */
    public RuntimeSettings(int ringBuffer, long idleTimeoutSec, int maxEventBytes,
                           String defaultPermissionMode, String claudePath) {
        this(ringBuffer, idleTimeoutSec, maxEventBytes, defaultPermissionMode, claudePath, true);
    }

    public static RuntimeSettings defaults() {
        return new RuntimeSettings(1000, 0, 100 * 1024, "acceptEdits", "");
    }

    public RuntimeSettings withClaudePath(String path) {
        return new RuntimeSettings(ringBuffer, idleTimeoutSec, maxEventBytes, defaultPermissionMode,
                path == null ? "" : path, includePartialMessages);
    }

    public RuntimeSettings withPermissionMode(String mode) {
        return new RuntimeSettings(ringBuffer, idleTimeoutSec, maxEventBytes,
                mode == null || mode.isBlank() ? defaultPermissionMode : mode, claudePath,
                includePartialMessages);
    }

    /** CAP-50：agent.properties 的 {@code partialMessages}（默认 true）由此注入。 */
    public RuntimeSettings withIncludePartialMessages(boolean enabled) {
        return new RuntimeSettings(ringBuffer, idleTimeoutSec, maxEventBytes, defaultPermissionMode,
                claudePath, enabled);
    }
}
