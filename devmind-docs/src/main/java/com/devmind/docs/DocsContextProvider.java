package com.devmind.docs;

import com.devmind.common.agent.ProjectAssetsProvider;
import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-33 FR-03 文档资产上下文 Provider（common {@link ContextProvider} SPI 实现）：
 * 绑定文档两级投递——摘要（标题 + 正文截断 {@value #SUMMARY_MAX} 字）进 CLAUDE.md
 * 「## 项目文档」节并留 .devmind/docs/&lt;docId&gt;.md 路径索引；全文作
 * {@link ContextPackage.DocEntry} 入包，由 runner 物化，大文档不全量塞 prompt
 * （claude 需要时自行 Read）。
 *
 * <p>①③层显式绑定不过滤 status（draft 也注入——显式绑定即意图）；未知 id 严格 404
 * （与 skill export 同语义）。docs 无项目自动命中层（忽略 projectAuto）。</p>
 *
 * <p>节序约定：{@link Order}(20) —— 文档节在知识（10）之后、skill（30）之前。</p>
 */
@Component
@Order(20)
public class DocsContextProvider implements ContextProvider, ProjectAssetsProvider {

    private static final Logger log = LoggerFactory.getLogger(DocsContextProvider.class);

    /** CLAUDE.md「项目文档」节中每篇文档的摘要截断长度。 */
    static final int SUMMARY_MAX = 500;

    private final DocumentService documentService;

    public DocsContextProvider(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public ContextContribution contribute(ContextAssemblyRequest req) {
        // ①场景 + ③请求合并去重（保序，场景来源优先标注）
        Map<Long, String> ids = new LinkedHashMap<>();
        for (Long id : req.scenarioDocIds()) {
            ids.putIfAbsent(id, ManifestItem.SOURCE_SCENARIO);
        }
        for (Long id : req.extraDocIds()) {
            ids.putIfAbsent(id, ManifestItem.SOURCE_REQUEST);
        }
        if (ids.isEmpty()) {
            return ContextContribution.empty();
        }
        List<ContextPackage.DocEntry> docs = new ArrayList<>();
        List<ManifestItem> items = new ArrayList<>();
        StringBuilder section = new StringBuilder("\n---\n\n## 项目文档\n");
        for (Map.Entry<Long, String> e : ids.entrySet()) {
            DocDetail d = documentService.get(e.getKey(), null); // 当前版本正文；未知 id 抛 404
            String docId = String.valueOf(d.id());
            String path = ".devmind/docs/" + docId + ".md";
            docs.add(new ContextPackage.DocEntry(docId, d.title(), d.contentMd()));
            items.add(new ManifestItem(ManifestItem.KIND_DOC, docId, d.title(), null, e.getValue(),
                    Map.of("version", d.versionNo(), "status", d.status(), "path", path)));
            section.append("\n### ").append(d.title()).append("\n\n")
                    .append(truncate(d.contentMd(), SUMMARY_MAX)).append("\n\n")
                    .append("> 全文见 `").append(path).append("`（v").append(d.versionNo())
                    .append("），需要时用 Read 工具查看。\n");
        }
        log.info("文档上下文装配: project={} 文档数={} dryRun={}", req.projectId(), docs.size(), req.dryRun());
        return new ContextContribution(List.of(section.toString()), List.of(), docs, null, items);
    }

    @Override
    public String kind() {
        return ManifestItem.KIND_DOC;
    }

    /** 项目「上下文」页签文档视图：该项目全部文档（元数据，与 /api/documents?projectId= 同口径）。 */
    @Override
    public List<ProjectAssetItem> listByProject(String projectId) {
        return documentService.list(null, projectId, null).stream()
                .map(this::toAssetItem)
                .toList();
    }

    private ProjectAssetItem toAssetItem(DocView d) {
        return new ProjectAssetItem(String.valueOf(d.id()), d.title(),
                d.kind() + " · " + d.status() + " · v" + d.currentVersion(),
                Map.of("kind", d.kind(), "status", d.status(), "tags", d.tags(),
                        "currentVersion", d.currentVersion()));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
