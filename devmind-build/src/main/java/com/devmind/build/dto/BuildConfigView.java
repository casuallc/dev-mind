package com.devmind.build.dto;

public record BuildConfigView(String projectId, String executor, String agentNodeId, int concurrencyLimit,
                              /** CAP-73：部署物树目录（相对工作区根，与验证环境 patch_dir 同构；可空） */
                              String outputDir) {
}
