package com.devmind.security.dto;

/**
 * 项目级扫描配置（CAP-66 FR-13）。
 *
 * @param internalGroupPrefixes 项目追加的内部构件 groupId 前缀（逗号分隔；平台默认前缀在
 *                              application.yml 的 devmind.security.internal-group-prefixes）
 */
public record SecurityConfigRequest(String internalGroupPrefixes) {
}
