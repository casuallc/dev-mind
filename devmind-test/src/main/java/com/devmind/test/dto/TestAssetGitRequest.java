package com.devmind.test.dto;

/** CAP-104 登记 git 测试资产（仓库 + 分支 + 可选固定 commit）。 */
public record TestAssetGitRequest(String name, String repoUrl, String branch, String commitHash, String note) {
}
