package com.devmind.flow;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.attachment.IssueAttachmentResolver;
import com.devmind.project.RequirementService;
import com.devmind.project.model.RequirementEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAP-40 需求附件装配 provider（节序 40，在 knowledge 10/docs 20/skill 30 之后）：
 * 会话挂 requirementId 时，把需求 description 引用的附件字节打进上下文包
 * （物化为 .devmind/input/），agent 用 Read 读图/读附件。
 *
 * <p>两类引用按需求来源分流（不会混）：本地需求（Markdown）提取
 * {@code /api/attachments/{id}/raw} 链接走 {@link AttachmentContentResolver}；
 * Jira 来源需求（wiki 原文）提取 {@code !name.png!} 标记走 {@link IssueAttachmentResolver}。
 * 两个附件源均 ObjectProvider 探测注入，未装配 = 无该源（清单标注不可用，不报错）。</p>
 *
 * <p>降级策略：单附件缺失/拉取失败跳过并在 CLAUDE.md 节标注原因，不阻断装配
 * （Jira 是远程 HTTP、本地附件可能已删）；上限 10 个/单文件 5MB/合计 20MB，超限截断注明。</p>
 */
@Component
@Order(40)
public class RequirementAttachmentProvider implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(RequirementAttachmentProvider.class);

    /** Markdown 内嵌附件链接：/api/attachments/{32hex}/raw */
    private static final Pattern LOCAL_REF = Pattern.compile("/api/attachments/([0-9a-f]{32})/raw");
    /** Jira wiki 图片标记：!文件名! 或 !文件名|attrs!（与前端 JiraDescription 同款） */
    private static final Pattern JIRA_REF = Pattern.compile("!([^!\\n|]+?)(\\|[^!\\n]*)?!");
    /** 物化文件名字符白名单（与 ContextMaterializer 一致） */
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    private static final int MAX_FILES = 10;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    /** contentType → 扩展名兜底（本地附件无原始名可取经 SPI，按 mime 给扩展名） */
    private static final Map<String, String> EXT_BY_MIME = Map.of(
            "image/png", ".png", "image/jpeg", ".jpg", "image/gif", ".gif",
            "image/webp", ".webp", "image/svg+xml", ".svg", "text/plain", ".txt",
            "application/pdf", ".pdf", "application/json", ".json");

    private final RequirementService requirementService;
    private final ObjectProvider<AttachmentContentResolver> attachmentResolverProvider;
    private final ObjectProvider<IssueAttachmentResolver> issueResolverProvider;

    public RequirementAttachmentProvider(RequirementService requirementService,
                                         ObjectProvider<AttachmentContentResolver> attachmentResolverProvider,
                                         ObjectProvider<IssueAttachmentResolver> issueResolverProvider) {
        this.requirementService = requirementService;
        this.attachmentResolverProvider = attachmentResolverProvider;
        this.issueResolverProvider = issueResolverProvider;
    }

    @Override
    public ContextContribution contribute(ContextAssemblyRequest req) {
        String requirementId = req.requirementId();
        if (requirementId == null || requirementId.isBlank()) {
            return ContextContribution.empty();
        }
        RequirementEntity requirement;
        try {
            requirement = requirementService.requireById(requirementId);
        } catch (Exception e) {
            log.warn("需求附件装配：需求读取失败（跳过）: {} err={}", requirementId, e.getMessage());
            return ContextContribution.empty();
        }
        String description = requirement.getDescription();
        if (description == null || description.isBlank()) {
            return ContextContribution.empty();
        }

        boolean jiraSource = RequirementEntity.SOURCE_JIRA.equals(requirement.getSource());
        List<ContextPackage.InputFile> inputs = new ArrayList<>();
        List<ManifestItem> items = new ArrayList<>();
        List<String> sectionLines = new ArrayList<>();
        long totalBytes = 0;
        int omitted = 0;

        // 按描述中引用出现顺序去重处理
        for (String ref : extractRefs(description, jiraSource)) {
            if (inputs.size() + omitted >= MAX_FILES) {
                omitted++;
                continue;
            }
            Resolved resolved = jiraSource
                    ? resolveJira(requirementId, ref)
                    : resolveLocal(ref);
            if (resolved == null) {
                sectionLines.add("- ❌ `" + ref + "`（" + (jiraSource ? "Jira 内嵌图" : "本地附件")
                        + "）不可用：附件不存在或读取失败");
                continue;
            }
            if (resolved.bytes().length > MAX_FILE_BYTES) {
                sectionLines.add("- ⚠️ `" + ref + "` 超过单文件上限 5MB，已省略");
                omitted++;
                continue;
            }
            if (totalBytes + resolved.bytes().length > MAX_TOTAL_BYTES) {
                omitted++;
                continue;
            }
            totalBytes += resolved.bytes().length;
            inputs.add(new ContextPackage.InputFile(resolved.path(), ref, resolved.contentType(),
                    Base64.getEncoder().encodeToString(resolved.bytes())));
            sectionLines.add("- `.devmind/input/" + resolved.path() + "`（" + resolved.sourceLabel()
                    + (resolved.contentType() != null ? "，" + resolved.contentType() : "") + "）");
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("path", ".devmind/input/" + resolved.path());
            extra.put("sizeBytes", resolved.bytes().length);
            items.add(new ManifestItem(ManifestItem.KIND_ATTACHMENT, resolved.manifestRef(),
                    resolved.path(), null, ManifestItem.SOURCE_REQUEST, extra));
        }

        if (inputs.isEmpty() && sectionLines.isEmpty()) {
            return ContextContribution.empty();
        }
        if (omitted > 0) {
            sectionLines.add("- ⚠️ 超出投送上限（最多 " + MAX_FILES + " 个 / 合计 20MB），已省略 "
                    + omitted + " 个附件");
        }

        StringBuilder section = new StringBuilder("\n---\n\n## 需求附件\n\n")
                .append("需求描述中引用了以下附件，已物化到会话工作区（相对 worktree 根）。")
                .append("分析/设计前请先用 Read 工具逐个查看，再动手：\n\n");
        for (String line : sectionLines) {
            section.append(line).append('\n');
        }
        log.info("需求附件装配: requirement={} 投送={} 不可用/省略={} 总字节={}",
                requirementId, inputs.size(), sectionLines.size() - inputs.size(), totalBytes);
        return new ContextContribution(List.of(section.toString()), List.of(), List.of(), null,
                items, inputs);
    }

    /** 按出现顺序提取引用（去重保序）：本地需求取附件 id，Jira 需求取 wiki 图片文件名。 */
    private List<String> extractRefs(String description, boolean jiraSource) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher m = (jiraSource ? JIRA_REF : LOCAL_REF).matcher(description);
        while (m.find()) {
            String ref = m.group(1).trim();
            if (!ref.isBlank() && !ref.startsWith("http://") && !ref.startsWith("https://")) {
                refs.add(ref);
            }
        }
        return List.copyOf(refs);
    }

    /** 本地附件：attachmentId → 字节；路径 {id}{mime 扩展名}。 */
    private Resolved resolveLocal(String attachmentId) {
        AttachmentContentResolver resolver = attachmentResolverProvider.getIfAvailable();
        if (resolver == null) {
            return null;
        }
        return resolver.resolveAny(attachmentId)
                .map(r -> {
                    String ext = EXT_BY_MIME.getOrDefault(
                            r.contentType() != null ? r.contentType().toLowerCase(java.util.Locale.ROOT) : "",
                            ".bin");
                    return new Resolved(attachmentId + ext, r.contentType(), r.bytes(),
                            "本地附件", attachmentId);
                })
                .orElse(null);
    }

    /** Jira 内嵌图：文件名 → 字节；路径 jira-{issueKey}-{safeName}。 */
    private Resolved resolveJira(String requirementId, String filename) {
        IssueAttachmentResolver resolver = issueResolverProvider.getIfAvailable();
        if (resolver == null) {
            return null;
        }
        return resolver.resolve(requirementId, filename)
                .map(r -> {
                    String safeName = UNSAFE_CHARS.matcher(r.filename()).replaceAll("_");
                    return new Resolved("jira-" + r.issueKey() + "-" + safeName, r.contentType(),
                            r.bytes(), "Jira issue " + r.issueKey() + " 内嵌图",
                            "jira:" + r.issueKey() + ":" + r.filename());
                })
                .orElse(null);
    }

    /** 解析成功的附件：物化路径/内容/来源标注/清单 ref。 */
    private record Resolved(String path, String contentType, byte[] bytes, String sourceLabel,
                            String manifestRef) {
    }
}
