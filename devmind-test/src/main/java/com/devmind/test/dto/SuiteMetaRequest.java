package com.devmind.test.dto;

/** CAP-104 套件资产属性（模块/负责人/标签/固定 commit/来源资产）。 */
public record SuiteMetaRequest(Long moduleId, String owner, String tags, String commitHash, Long sourceAssetId) {
}
