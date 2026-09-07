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
 */
public record RuntimeSettings(int ringBuffer, long idleTimeoutSec, int maxEventBytes,
                              String defaultPermissionMode, String claudePath) {

    public static RuntimeSettings defaults() {
        return new RuntimeSettings(1000, 0, 100 * 1024, "acceptEdits", "");
    }

    public RuntimeSettings withClaudePath(String path) {
        return new RuntimeSettings(ringBuffer, idleTimeoutSec, maxEventBytes, defaultPermissionMode,
                path == null ? "" : path);
    }

    public RuntimeSettings withPermissionMode(String mode) {
        return new RuntimeSettings(ringBuffer, idleTimeoutSec, maxEventBytes,
                mode == null || mode.isBlank() ? defaultPermissionMode : mode, claudePath);
    }
}
