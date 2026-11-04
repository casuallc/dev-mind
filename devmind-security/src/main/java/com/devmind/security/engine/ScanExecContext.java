package com.devmind.security.engine;

/**
 * 扫描执行上下文（CAP-66 FR-03）：渲染 exec 脚本的输入。
 * 上传地址/token 走 env（{@code DEVMIND_SCAN_UPLOAD_URL}/{@code DEVMIND_SCAN_UPLOAD_TOKEN}）
 * 注入不进脚本串——脚本全文会进执行审计与日志，token 不落明文。
 *
 * @param scanId 扫描任务 id（脚本内报告目录命名等用）
 */
public record ScanExecContext(long scanId) {

    /** env：报告上行绝对地址（含 scanId 路径段） */
    public static final String ENV_UPLOAD_URL = "DEVMIND_SCAN_UPLOAD_URL";
    /** env：一次性上传 token（明文仅随帧下发，服务端只存哈希） */
    public static final String ENV_UPLOAD_TOKEN = "DEVMIND_SCAN_UPLOAD_TOKEN";
    /** env：扫描引擎标记——runner 据此解析 scan.<engine>.path 并 prepend 进子进程 PATH */
    public static final String ENV_SCAN_ENGINE = "DEVMIND_SCAN_ENGINE";
}
