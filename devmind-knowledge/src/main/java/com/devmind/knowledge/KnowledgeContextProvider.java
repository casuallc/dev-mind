package com.devmind.knowledge;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.common.knowledge.KnowledgeRetriever.RetrievedChunk;
import com.devmind.knowledge.config.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-33 知识资产上下文 Provider（common {@link ContextProvider} SPI 实现，装配管线经
 * ObjectProvider 收集）。CAP-92 起不再有全量注入：场景绑定知识库 id，装配时用渲染后的
 * 任务说明作 query 检索命中块，按条目聚合后渲染为「相关知识（检索命中）」节。
 * 检索失败/无命中降级为空知识节（不阻塞会话）。
 *
 * <p>settings.local.json 权限白名单（服务端策略）由本 Provider 产出（契约：全管线唯一来源）。</p>
 *
 * <p>节序约定：{@link Order}(10) —— 知识节在 docs（20）/skill（30）之前。</p>
 */
@Component
@Order(10)
public class KnowledgeContextProvider implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeContextProvider.class);

    /** 检索 query 截断上限：任务说明可能很长，取尾部（通常是真正的任务描述）。 */
    private static final int QUERY_MAX = 1000;

    /** 注入会话工作区的权限白名单（服务端策略，物化由 runner 完成）。 */
    public static final String SETTINGS_LOCAL_JSON = "{\n" +
            "  \"permissions\": {\n" +
            "    \"allow\": [\"Bash(npm:*)\", \"Bash(mvn:*)\", \"Bash(git:*)\", \"Edit\", \"Write\", \"Read\"]\n" +
            "  }\n" +
            "}\n";

    private final KnowledgeProperties props;
    private final KnowledgeBaseService service;
    private final ObjectProvider<KnowledgeRetriever> retrieverProvider;

    public KnowledgeContextProvider(KnowledgeProperties props, KnowledgeBaseService service,
                                    ObjectProvider<KnowledgeRetriever> retrieverProvider) {
        this.props = props;
        this.service = service;
        this.retrieverProvider = retrieverProvider;
    }

    @Override
    public ContextContribution contribute(ContextAssemblyRequest req) {
        if (!props.isEnabled()) {
            return ContextContribution.empty();
        }
        // settings 白名单独立于知识命中：瘦上下文（执行会话）也要靠它拿到权限策略
        ContextContribution settingsOnly = new ContextContribution(
                List.of(), List.of(), List.of(), SETTINGS_LOCAL_JSON, List.of());
        List<Long> kbIds = req.scenarioKnowledgeBaseIds();
        String query = req.knowledgeQuery() == null ? "" : req.knowledgeQuery().strip();
        if (kbIds == null || kbIds.isEmpty() || query.isEmpty()) {
            return settingsOnly;
        }
        KnowledgeRetriever retriever = retrieverProvider.getIfAvailable();
        if (retriever == null) {
            log.debug("知识检索未装配，跳过知识节: project={}", req.projectId());
            return settingsOnly;
        }
        List<RetrievedChunk> hits;
        try {
            KnowledgeRetriever.Detailed detailed = retriever.retrieveDetailed(
                    kbIds, truncateQuery(query), props.getEmbedding().getTopK());
            hits = detailed.chunks() == null ? List.of() : detailed.chunks();
        } catch (Exception e) {
            log.warn("知识检索失败，按无命中降级: project={} kbs={} err={}",
                    req.projectId(), kbIds, e.toString());
            return settingsOnly;
        }
        // 按条目聚合（保序）：同一文档多个命中块合成一节，清单也只出一条
        Map<Long, List<RetrievedChunk>> byEntry = new LinkedHashMap<>();
        for (RetrievedChunk hit : hits) {
            if (hit != null && hit.entryId() != null) {
                byEntry.computeIfAbsent(hit.entryId(), k -> new ArrayList<>()).add(hit);
            }
        }
        if (byEntry.isEmpty()) {
            log.info("知识检索无命中: project={} kbs={}", req.projectId(), kbIds);
            return settingsOnly;
        }
        if (!req.dryRun()) {
            service.bumpHitsByEntryIds(byEntry.keySet()); // FR-07 清理依据；dryRun 预览零副作用
        }
        Map<Long, String> kbNames = retriever.baseNames(kbIds);
        // CAP-94：库不再分层，manifest scope 字段改输出库名
        List<ManifestItem> items = manifestItems(byEntry, kbNames);
        log.info("知识上下文装配: project={} kbs={} 条目={} 分块={} dryRun={}",
                req.projectId(), kbIds, items.size(), hits.size(), req.dryRun());
        return new ContextContribution(List.of(renderSection(byEntry, kbNames)), List.of(), List.of(),
                SETTINGS_LOCAL_JSON, items);
    }

    private static List<ManifestItem> manifestItems(Map<Long, List<RetrievedChunk>> byEntry,
                                                    Map<Long, String> kbNames) {
        List<ManifestItem> items = new ArrayList<>(byEntry.size());
        for (Map.Entry<Long, List<RetrievedChunk>> e : byEntry.entrySet()) {
            RetrievedChunk first = e.getValue().get(0);
            String kbName = first.kbId() == null ? null : kbNames.get(first.kbId());
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("kbId", first.kbId());
            extra.put("kbName", kbName);
            extra.put("chunks", e.getValue().size());
            extra.put("sourceUri", first.sourceUri());
            items.add(new ManifestItem(ManifestItem.KIND_KNOWLEDGE, String.valueOf(e.getKey()),
                    entryName(first), kbName, ManifestItem.SOURCE_SCENARIO, extra));
        }
        return items;
    }

    private static String renderSection(Map<Long, List<RetrievedChunk>> byEntry, Map<Long, String> kbNames) {
        StringBuilder md = new StringBuilder("\n---\n\n## 相关知识（检索命中）\n\n");
        md.append("以下资料按本次任务从场景绑定的知识库中检索得到，可能不完整；引用时请注明来源。\n");
        for (List<RetrievedChunk> chunks : byEntry.values()) {
            RetrievedChunk first = chunks.get(0);
            md.append("\n### ").append(entryName(first)).append("\n\n");
            md.append("来源：").append(sourceLabel(first, kbNames)).append("\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk chunk = chunks.get(i);
                if (chunks.size() > 1) {
                    md.append("**片段 ").append(i + 1).append("**");
                    if (chunk.headingPath() != null && !chunk.headingPath().isBlank()) {
                        md.append("（").append(chunk.headingPath()).append("）");
                    }
                    md.append("\n\n");
                }
                md.append(chunk.content() == null ? "" : chunk.content().strip()).append("\n\n");
            }
        }
        return md.toString();
    }

    private static String entryName(RetrievedChunk hit) {
        return hit.entryName() == null || hit.entryName().isBlank()
                ? "知识条目 #" + hit.entryId() : hit.entryName();
    }

    private static String sourceLabel(RetrievedChunk hit, Map<Long, String> kbNames) {
        String path = hit.sourceUri() != null && !hit.sourceUri().isBlank()
                ? hit.sourceUri() : entryName(hit);
        String kb = hit.kbId() == null ? null : kbNames.get(hit.kbId());
        if (hit.headingPath() != null && !hit.headingPath().isBlank()) {
            path = path + " > " + hit.headingPath();
        }
        return kb == null ? path : path + "（库：" + kb + "）";
    }

    private static String truncateQuery(String query) {
        return query.length() <= QUERY_MAX ? query : query.substring(query.length() - QUERY_MAX);
    }
}
