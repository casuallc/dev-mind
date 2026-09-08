package com.devmind.skill;

import com.devmind.common.agent.ProjectAssetsProvider;
import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.skill.dto.SkillPackageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-33 FR-04 技能资产上下文 Provider（common {@link ContextProvider} SPI 实现）：
 * ①③层场景/请求显式 ids 走 {@link SkillService#exportPackages}（严格 404、DISABLED 跳过），
 * ②层项目私有 ACTIVE skill 默认全带（{@link SkillService#listActiveProjectSkillIds}）；
 * 合并去重（显式优先）后转换为 {@link ContextPackage.SkillPackage}（name = 落盘目录名，
 * files = 相对路径 → base64，LinkedHashMap 保序，SKILL.md 在前），
 * 由 runner 物化到 .claude/skills/&lt;name&gt;/ 被 Claude Code 原生识别。
 *
 * <p>skill 不产出 CLAUDE.md 节（Claude Code 自动发现 skills，无需索引）。</p>
 *
 * <p>节序约定：{@link Order}(30) —— 在知识（10）/文档（20）之后。</p>
 */
@Component
@Order(30)
public class SkillContextProvider implements ContextProvider, ProjectAssetsProvider {

    private static final Logger log = LoggerFactory.getLogger(SkillContextProvider.class);

    private final SkillService skillService;

    public SkillContextProvider(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public ContextContribution contribute(ContextAssemblyRequest req) {
        // ①③显式 + ②项目私有合并去重（保序），source 以显式层优先标注
        Map<String, String> ids = new LinkedHashMap<>();
        for (String id : req.scenarioSkillIds()) {
            ids.putIfAbsent(id, ManifestItem.SOURCE_SCENARIO);
        }
        for (String id : req.extraSkillIds()) {
            ids.putIfAbsent(id, ManifestItem.SOURCE_REQUEST);
        }
        if (req.projectAuto()) {
            for (String id : skillService.listActiveProjectSkillIds(req.projectId())) {
                ids.putIfAbsent(id, ManifestItem.SOURCE_PROJECT_AUTO);
            }
        }
        if (ids.isEmpty()) {
            return ContextContribution.empty();
        }
        // exportPackages：未知 id 抛 404，DISABLED 跳过（显式绑定一个 DISABLED = 静默不注入，
        // 与导出语义一致；清单里也就看不到它）
        SkillPackageView view = skillService.exportPackages(new ArrayList<>(ids.keySet()));
        List<ContextPackage.SkillPackage> skills = new ArrayList<>();
        List<ManifestItem> items = new ArrayList<>();
        for (SkillPackageView.SkillPackageItem item : view.items()) {
            Map<String, String> files = new LinkedHashMap<>();
            for (SkillPackageView.ExportedFile f : item.files()) {
                files.put(f.path(), f.contentBase64());
            }
            skills.add(new ContextPackage.SkillPackage(item.name(), files));
            items.add(new ManifestItem(ManifestItem.KIND_SKILL, item.skillId(), item.name(),
                    item.scope(), ids.get(item.skillId()), Map.of("files", files.size())));
        }
        log.info("技能上下文装配: project={} 技能数={} dryRun={}", req.projectId(), skills.size(), req.dryRun());
        return new ContextContribution(List.of(), skills, List.of(), null, items);
    }

    @Override
    public String kind() {
        return ManifestItem.KIND_SKILL;
    }

    /** 项目「上下文」页签技能视图：该项目私有 skill 全量（含 DISABLED，只读视图全量展示）。 */
    @Override
    public List<ProjectAssetItem> listByProject(String projectId) {
        return skillService.list("PROJECT", projectId, null, null, 0, 200).items().stream()
                .map(s -> new ProjectAssetItem(s.id(), s.name(),
                        s.description() == null ? "" : s.description(),
                        Map.of("status", s.status(), "tags", s.tags(), "fileCount", s.fileCount())))
                .toList();
    }
}
