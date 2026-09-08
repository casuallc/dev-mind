package com.devmind.common.agent;

import java.util.List;
import java.util.Map;

/**
 * CAP-33 FR-06 项目「上下文」页签资产聚合 SPI（common 定义，knowledge/docs/skill 各实现，
 * 可与 {@link com.devmind.common.agent.exec.ContextProvider} 同 bean 双实现）：
 * session 模块的 GET /api/projects/{id}/context-assets 经 ObjectProvider 收集聚合，
 * 各模块按「该项目实际会注入/可用的资产」口径出清单（只读视图，前端分三组渲染）。
 */
public interface ProjectAssetsProvider {

    /** 资产类型：knowledge | skill | doc。 */
    String kind();

    List<ProjectAssetItem> listByProject(String projectId);

    /**
     * @param ref     资产主键串（前端跳转 /admin 管理页用）
     * @param summary 一句话摘要（描述/正文截断）
     * @param extra   类型相关附加信息（tags/status/updatedAt 等），可空 map
     */
    record ProjectAssetItem(String ref, String name, String summary, Map<String, Object> extra) {
    }
}
