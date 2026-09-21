package com.devmind.session.service;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextManifest;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextPackages;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAP-33 FR-02 上下文装配管线（服务端数据侧）：经 common {@link ContextProvider} SPI 收集
 * knowledge/docs/skill 三个模块的产出（ObjectProvider 探测注入，不反向依赖实现），
 * 拼成最终 {@link ContextPackage} 与 FR-07 可追溯快照。
 *
 * <p>CLAUDE.md 节序（保持 CAP-04 结构）：头部注释 → ## 场景背景（场景 extraContextMd）
 * → 各 provider 节（@Order：知识 10 / 文档 20 / skill 30）→ ## 当前任务（渲染后 taskSpec）。
 * settings.local.json 契约上仅 knowledge provider 产出，取第一个非空。</p>
 *
 * <p>空产出（无场景背景/无条目节/无 skills/无 docs/无附件 **且无 settings**）= null：不带上下文
 * 启动，沿用 CAP-34 之前「知识无命中 = 无注入」的语义。CAP-52 起例外：**只有权限白名单**时
 * 必须出包（瘦上下文的执行会话靠它拿到 settings.local.json）。</p>
 */
@Service
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    private static final String HEADER =
            "<!-- 由 Dev-Mind 上下文装配管线自动生成（CAP-33），请勿手改本文件；本文件为平台托管产物，不入版本控制 -->\n";

    private final ObjectProvider<ContextProvider> providers;
    private final ObjectMapper mapper;

    public ContextAssembler(ObjectProvider<ContextProvider> providers, ObjectMapper mapper) {
        this.providers = providers;
        this.mapper = mapper;
    }

    /** 装配结果：包（缓存供 runner 拉取）+ 随帧 manifest + FR-07 快照 JSON（落库）+ 清单项（预览用）。 */
    public record AssembledContext(ContextPackage pkg, ContextManifest manifest, String snapshotJson,
                                   List<ManifestItem> items) {
    }

    /**
     * 装配。provider 抛出的 DevMindException（如场景绑定的资产已删除，严格 404）向上传播
     * 让创建方 fail-visible；其它异常由调用方决定降级策略。
     */
    public AssembledContext assemble(ContextAssemblyRequest req, String scenarioCode, String scenarioName,
                                     String extraContextMd, String renderedTaskSpec) {
        List<ContextContribution> contribs = providers.orderedStream()
                .map(p -> p.contribute(req))
                .toList();

        List<String> sections = new ArrayList<>();
        List<ContextPackage.SkillPackage> skills = new ArrayList<>();
        List<ContextPackage.DocEntry> docs = new ArrayList<>();
        List<ContextPackage.InputFile> inputs = new ArrayList<>();
        List<ManifestItem> items = new ArrayList<>();
        String settings = null;
        for (ContextContribution c : contribs) {
            sections.addAll(c.claudeMdSections());
            skills.addAll(c.skills());
            docs.addAll(c.docs());
            items.addAll(c.items());
            inputs.addAll(c.inputs());
            if (settings == null && c.settingsLocalJson() != null) {
                settings = c.settingsLocalJson();
            }
        }

        boolean hasScenarioBg = extraContextMd != null && !extraContextMd.isBlank();
        // CAP-40：附件投送也算产出——挂需求会话即使无场景无知识命中，有附件就必须出包
        // CAP-52：权限白名单（settings）**必须**独立成包——瘦上下文（执行会话）没有任何内容节，
        // 若仍按「空产出 = null」返回，runner 拉不到包就不会物化 settings.local.json，
        // headless claude 会在权限提示上卡死。白名单是沙箱策略，不是「上下文注入」。
        boolean contentless = !hasScenarioBg && sections.isEmpty() && skills.isEmpty()
                && docs.isEmpty() && inputs.isEmpty();
        if (contentless && settings == null) {
            return null;
        }

        StringBuilder md = new StringBuilder(HEADER);
        if (!contentless) {
            if (hasScenarioBg) {
                md.append("\n---\n\n## 场景背景\n\n").append(extraContextMd.strip()).append("\n");
            }
            for (String section : sections) {
                md.append(section);
            }
            // 无内容节时不重复注入任务说明：taskSpec 已作为首条 stdin 消息下发，再写一遍纯属浪费 token
            md.append("\n---\n\n## 当前任务\n\n")
                    .append(renderedTaskSpec == null ? "" : renderedTaskSpec.strip()).append("\n");
        }

        ContextPackage pkg = ContextPackage.of(md.toString(), settings, skills, docs, inputs);
        ContextManifest manifest = ContextPackages.manifestOf(ContextPackages.toJsonBytes(pkg), items.size());
        String snapshotJson = snapshotJson(scenarioCode, scenarioName, renderedTaskSpec, hasScenarioBg,
                items, manifest);
        log.info("上下文装配完成: scenario={} 条目={} skills={} docs={} 包字节={}",
                scenarioCode, items.size(), skills.size(), docs.size(), manifest.totalBytes());
        return new AssembledContext(pkg, manifest, snapshotJson, List.copyOf(items));
    }

    /** FR-07 快照：只存清单（id/name/source/路径等），不存包内容；重建 = 重跑装配。 */
    private String snapshotJson(String scenarioCode, String scenarioName, String renderedTaskSpec,
                                boolean hasExtraContext, List<ManifestItem> items, ContextManifest manifest) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("schemaVersion", 1);
        snap.put("scenarioCode", scenarioCode);
        snap.put("scenarioName", scenarioName);
        snap.put("assembledAt", Instant.now().toString());
        snap.put("renderedTaskSpecPreview", preview(renderedTaskSpec, 200));
        snap.put("hasExtraContext", hasExtraContext);
        snap.put("items", items);
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("entries", manifest.entries());
        pkg.put("totalBytes", manifest.totalBytes());
        pkg.put("sha256", manifest.sha256());
        snap.put("package", pkg);
        return mapper.writeValueAsString(snap);
    }

    private static String preview(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ').strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }
}
