package com.devmind.common.agent;

/**
 * CAP-57 pkg 帧模型（安装包分发下发 runner，协议 v15 起）。
 *
 * <p>帧里只放<b>引用与校验信息</b>，字节由 runner 凭节点 token 走 HTTP 拉取
 * （{@code GET /api/agent/classify/packages/{packageId}?token=}，照 CAP-56 lab bundle 与
 * runner 自升级同款的「节点拉取」模式——不建服务端推送通道，NAT 节点也能通）。</p>
 *
 * <p>runner 侧动作：流式下载 + sha256 校验（复用 RunnerUpgrader.downloadAndVerify 同款）→
 * 解 zip 到 {@code <installDir>.tmp} → 原子 rename 到 installDir → 回 pkg_ack；
 * 失败清理 .tmp 不留半成品。GB 级包下载耗时以分钟计，ack 异步，调用方不阻塞等待线程。</p>
 *
 * @param requestId  本次请求唯一 id（ack 路由键，[a-zA-Z0-9._-]）
 * @param packageId  安装包 id（classify_packages 主键，下载端点路径段）
 * @param sha256     包文件 sha256（hex，64 字符；runner 校验不过即失败，不落盘）
 * @param sizeBytes  包文件大小（下载完整性 + 进度展示）
 * @param fileName   原始文件名（人读与日志用）
 * @param installDir 安装目录（<b>相对 {@code <workspaceRoot>/classify/} 根</b>，
 *                   约定 {@code packages/pkg-<packageId>}；runner 拼 workspaceRoot 并收容校验）
 */
public record AgentPkgCommand(String requestId, long packageId, String sha256, long sizeBytes,
                              String fileName, String installDir) {
}
