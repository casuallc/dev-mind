package com.devmind.security.dto;

import java.util.List;

/**
 * 发起扫描请求（CAP-66 FR-01）。
 *
 * @param repoId      项目仓库 id（可空 = 主库）
 * @param branch      分支（可空 = 仓库默认分支 → 项目基准分支 → main）
 * @param engines     引擎子集（sca/sast/secrets，必填非空）
 * @param agentNodeId 显式指定执行节点（可空 = 项目默认 → 平台默认）
 */
public record CreateScanRequest(Long repoId, String branch, List<String> engines, String agentNodeId) {
}
