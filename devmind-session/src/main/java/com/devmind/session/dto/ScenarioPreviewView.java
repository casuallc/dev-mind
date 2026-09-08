package com.devmind.session.dto;

import com.devmind.common.agent.exec.ManifestItem;

import java.util.List;

/**
 * CAP-33 FR-06 场景预览（dryRun 装配，不 bumpHits）：骨架渲染产物 + 将会注入的 CLAUDE.md
 * 全文 + 三层来源标注的清单 + 包摘要。hasContext=false 时 claudeMd/items 为空
 * （= 真实创建时按无上下文启动）。
 *
 * @param docs  docId + title（全文在包内 .devmind/docs/<docId>.md，预览不展开）
 */
public record ScenarioPreviewView(
        String renderedTaskSpec, boolean hasContext, String claudeMd,
        List<ManifestItem> items, List<String> skills, List<DocPreview> docs,
        int entries, long totalBytes, String sha256) {

    public record DocPreview(String docId, String title) {
    }
}
