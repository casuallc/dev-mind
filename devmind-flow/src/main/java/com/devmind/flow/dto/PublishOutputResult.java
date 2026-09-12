package com.devmind.flow.dto;

/**
 * CAP-39 FR-03 推送结果。
 *
 * @param docId     落成/更新的文档 id
 * @param versionNo 文档当前版本号
 * @param designId  kind=design 且 create 时新建的 Design 记录 id（其余为 null）
 */
public record PublishOutputResult(Long docId, int versionNo, String designId) {
}
