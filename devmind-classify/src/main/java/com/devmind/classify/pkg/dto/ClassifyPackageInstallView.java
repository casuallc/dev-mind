package com.devmind.classify.pkg.dto;

import java.time.Instant;

/** CAP-57 安装记录视图（installDir 为节点侧绝对路径，供展示与 checkpoint 登记复制） */
public record ClassifyPackageInstallView(Long id, Long packageId, String packageName, String nodeId,
                                         String installDir, String status, String requestId,
                                         String error, Instant createdAt, Instant updatedAt) {
}
