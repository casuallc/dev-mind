package com.devmind.knowledge;

import com.devmind.common.agent.ProjectAssetsProvider;
import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.knowledge.config.KnowledgeProperties;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-33 知识资产上下文 Provider（common {@link ContextProvider} SPI 实现，装配管线经
 * ObjectProvider 收集）：①③层按场景/请求的 knowledgeTags 显式命中（{@link KnowledgeBaseService#selectByTags}），
 * ②层项目自动命中沿用 {@link KnowledgeBaseService#selectEntries} 现状口径；两路合并去重后逐项标注来源。
 * settings.local.json 权限白名单（服务端策略）由本 Provider 产出（契约：全管线唯一来源）。
 * dryRun（预览）不 bumpHits。
 *
 * <p>同时实现 {@link ProjectAssetsProvider}：项目「上下文」页签的知识视图 = 该项目实际会注入的条目。</p>
 *
 * <p>节序约定：{@link Order}(10) —— 知识节在 docs（20）/skill（30）之前。</p>
 */
@Component
@Order(10)
public class KnowledgeContextProvider implements ContextProvider, ProjectAssetsProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeContextProvider.class);

    /** 注入会话工作区的权限白名单（服务端策略，物化由 runner 完成）。 */
    public static final String SETTINGS_LOCAL_JSON = "{\n" +
            "  \"permissions\": {\n" +
            "    \"allow\": [\"Bash(npm:*)\", \"Bash(mvn:*)\", \"Bash(git:*)\", \"Edit\", \"Write\", \"Read\"]\n" +
            "  }\n" +
            "}\n";

    private final KnowledgeProperties props;
    private final KnowledgeBaseService service;
    private final ProjectService projectService;

    public KnowledgeContextProvider(KnowledgeProperties props, KnowledgeBaseService service,
                                    ProjectService projectService) {
        this.props = props;
        this.service = service;
        this.projectService = projectService;
    }

    @Override
    public ContextContribution contribute(ContextAssemblyRequest req) {
        if (!props.isEnabled()) {
            return ContextContribution.empty();
        }
        // 三层合并去重（保序），source 以先命中层为准（②自动命中优先标注，①③显式层不覆盖）
        Map<Long, EntryView> merged = new LinkedHashMap<>();
        Map<Long, String> sources = new LinkedHashMap<>();
        if (req.projectAuto()) {
            for (EntryView e : service.selectEntries(req.projectId(), req.projectTags())) {
                merged.putIfAbsent(e.id(), e);
                sources.putIfAbsent(e.id(), ManifestItem.SOURCE_PROJECT_AUTO);
            }
        }
        for (EntryView e : service.selectByTags(req.scenarioKnowledgeTags(), req.projectId())) {
            merged.putIfAbsent(e.id(), e);
            sources.putIfAbsent(e.id(), ManifestItem.SOURCE_SCENARIO);
        }
        for (EntryView e : service.selectByTags(req.extraKnowledgeTags(), req.projectId())) {
            merged.putIfAbsent(e.id(), e);
            sources.putIfAbsent(e.id(), ManifestItem.SOURCE_REQUEST);
        }
        List<EntryView> used = new ArrayList<>(merged.values());
        if (used.isEmpty()) {
            // settings 白名单仍产出：无知识命中但场景带了 skills/docs 时包内仍应有权限策略
            return new ContextContribution(List.of(), List.of(), List.of(), SETTINGS_LOCAL_JSON, List.of());
        }
        if (!req.dryRun()) {
            service.bumpHits(used); // FR-07 清理依据；dryRun 预览零副作用
        }
        List<ManifestItem> items = used.stream()
                .map(e -> new ManifestItem(ManifestItem.KIND_KNOWLEDGE, String.valueOf(e.id()),
                        e.name(), e.scope(), sources.get(e.id()), null))
                .toList();
        String sections = ClaudeMd.renderEntrySections(used);
        log.info("知识上下文装配: project={} 条目={} dryRun={}", req.projectId(), used.size(), req.dryRun());
        return new ContextContribution(
                sections.isBlank() ? List.of() : List.of(sections),
                List.of(), List.of(), SETTINGS_LOCAL_JSON, items);
    }

    @Override
    public String kind() {
        return ManifestItem.KIND_KNOWLEDGE;
    }

    /** 项目「上下文」页签知识视图：与真实注入同口径（global 按 tags 命中 + 项目条目全量）。 */
    @Override
    public List<ProjectAssetItem> listByProject(String projectId) {
        Project project = projectService.requireProject(projectId);
        return service.selectEntries(project).stream()
                .map(e -> new ProjectAssetItem(String.valueOf(e.id()), e.name(),
                        truncate(e.contentMd(), 100),
                        Map.of("scope", e.scope(), "tags", e.tags(), "hitCount", e.hitCount())))
                .toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.strip().replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
