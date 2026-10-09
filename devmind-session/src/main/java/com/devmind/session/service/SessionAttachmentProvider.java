package com.devmind.session.service;

import com.devmind.common.agent.exec.ContextAssemblyRequest;
import com.devmind.common.agent.exec.ContextContribution;
import com.devmind.common.agent.exec.ContextPackage;
import com.devmind.common.agent.exec.ContextProvider;
import com.devmind.common.agent.exec.ManifestItem;
import com.devmind.common.attachment.AttachmentContentResolver;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
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
import java.util.regex.Pattern;

/**
 * CAP-68 FR-07 会话附件装配 provider（节序 50，在需求附件 40 之后）：创建会话时显式附带的
 * 附件（{@code CreateSessionRequest.attachmentIds}，落库 sessions.attachment_ids）打进上下文包
 * inputs，物化为 {@code .devmind/input/attachments/<attachmentId>-<净化文件名>}，agent 起手
 * 即可 Read。
 *
 * <p>与 CAP-40 需求附件的差异：来源是③请求级显式选择而非需求描述引用解析，故附件模块未装配时
 * <b>抛错 fail-visible</b>（用户刚选的附件不能静默丢，与 FR-05/06 输入链路同口径）；单个附件
 * 已删/超限则跳过并在 CLAUDE.md 节标注（TTL 重建口径必须容忍附件后来被生命周期硬删，
 * 否则 rebuild 抛错会让 runner 拉包失败、会话起不来）。限额对齐 CAP-40：≤10 个/单文件 5MB/
 * 合计 20MB，超限截断注明。文件名净化走 ASCII 白名单（ContextMaterializer 口径），中文名
 * 退化保 attachmentId 前缀仍可追溯。</p>
 */
@Component
@Order(50)
public class SessionAttachmentProvider implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(SessionAttachmentProvider.class);

    /** 物化文件名字符白名单（与 ContextMaterializer / CAP-40 一致：ASCII，中文剥掉） */
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    private static final int MAX_FILES = 10;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    /** contentType → 扩展名兜底（原始名为空/净化后为空时按 mime 给扩展名，同 CAP-40） */
    private static final Map<String, String> EXT_BY_MIME = Map.of(
            "image/png", ".png", "image/jpeg", ".jpg", "image/gif", ".gif",
            "image/webp", ".webp", "image/svg+xml", ".svg", "text/plain", ".txt",
            "application/pdf", ".pdf", "application/json", ".json",
            "text/markdown", ".md", "text/csv", ".csv");

    private final ObjectProvider<AttachmentContentResolver> resolverProvider;

    public SessionAttachmentProvider(ObjectProvider<AttachmentContentResolver> resolverProvider) {
        this.resolverProvider = resolverProvider;
    }

    @Override
    public ContextContribution contribute(ContextAssemblyRequest req) {
        List<String> ids = req.attachmentIds();
        if (ids == null || ids.isEmpty()) {
            return ContextContribution.empty();
        }
        AttachmentContentResolver resolver = resolverProvider.getIfAvailable();
        if (resolver == null) {
            // 用户创建时显式选的附件：模块未装配 = 确定性失败，create/rebuild 同口径抛错，不静默丢
            throw new DevMindException(ErrorCode.CONFLICT,
                    "附件模块未装配，无法投送会话附件（" + ids.size() + " 个）");
        }

        List<ContextPackage.InputFile> inputs = new ArrayList<>();
        List<ManifestItem> items = new ArrayList<>();
        List<String> sectionLines = new ArrayList<>();
        long totalBytes = 0;
        int omitted = 0;

        // 去重保序（用户可能重复选择同一附件）
        Set<String> seen = new LinkedHashSet<>(ids);
        for (String attachmentId : seen) {
            if (attachmentId == null || attachmentId.isBlank()) {
                continue;
            }
            if (inputs.size() + omitted >= MAX_FILES) {
                omitted++;
                continue;
            }
            var resolved = resolver.resolveAny(attachmentId).orElse(null);
            if (resolved == null) {
                // 附件可能已被生命周期硬删（CAP-68 FR-03 不查引用）：跳过标注，不阻断装配
                sectionLines.add("- ❌ 附件 `" + attachmentId + "` 不存在或已过期删除，未投送");
                continue;
            }
            if (resolved.bytes().length > MAX_FILE_BYTES) {
                sectionLines.add("- ⚠️ `" + displayName(resolved) + "` 超过单文件上限 5MB，已省略");
                omitted++;
                continue;
            }
            if (totalBytes + resolved.bytes().length > MAX_TOTAL_BYTES) {
                omitted++;
                continue;
            }
            totalBytes += resolved.bytes().length;
            String path = "attachments/" + attachmentId + "-" + safeName(resolved);
            inputs.add(new ContextPackage.InputFile(path, displayName(resolved), resolved.contentType(),
                    Base64.getEncoder().encodeToString(resolved.bytes())));
            sectionLines.add("- `.devmind/input/" + path + "`（" + displayName(resolved)
                    + (resolved.contentType() != null ? "，" + resolved.contentType() : "") + "）");
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("path", ".devmind/input/" + path);
            extra.put("sizeBytes", resolved.bytes().length);
            items.add(new ManifestItem(ManifestItem.KIND_ATTACHMENT, attachmentId, path, null,
                    ManifestItem.SOURCE_REQUEST, extra));
        }

        if (inputs.isEmpty() && sectionLines.isEmpty()) {
            return ContextContribution.empty();
        }
        if (omitted > 0) {
            sectionLines.add("- ⚠️ 超出投送上限（最多 " + MAX_FILES + " 个 / 合计 20MB），已省略 "
                    + omitted + " 个附件");
        }

        StringBuilder section = new StringBuilder("\n---\n\n## 会话附件\n\n")
                .append("创建会话时用户附带了以下文件，已物化到会话工作区（相对 worktree 根）。")
                .append("开始工作前请先用 Read 工具逐个查看：\n\n");
        for (String line : sectionLines) {
            section.append(line).append('\n');
        }
        log.info("会话附件装配: 投送={} 不可用/省略={} 总字节={}",
                inputs.size(), sectionLines.size() - inputs.size(), totalBytes);
        return new ContextContribution(List.of(section.toString()), List.of(), List.of(), null,
                items, inputs);
    }

    /**
     * 物化文件名：原始名 ASCII 白名单净化（basename 防穿越；中文等非白名单字符剥掉）。
     * 净化结果为空/无扩展名时按 mime 兜底扩展名，保证 agent 看扩展名知类型。
     */
    static String safeName(AttachmentContentResolver.ResolvedAttachment r) {
        String raw = r.originalName() == null ? "" : r.originalName().trim();
        // basename 防穿越（与 runner 侧 sanitizeIncomingName 同义，但这里走 ASCII 白名单）
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        if (slash >= 0) {
            raw = raw.substring(slash + 1);
        }
        String safe = UNSAFE_CHARS.matcher(raw).replaceAll("");
        if (safe.length() > 80) {
            safe = safe.substring(safe.length() - 80); // 保扩展名一侧
        }
        if (safe.isBlank() || safe.startsWith(".") && safe.indexOf('.', 1) < 0) {
            // 全中文名净化后只剩扩展名或为空：补占位名
            safe = "attachment" + (safe.isBlank() ? extOf(r.contentType()) : safe);
        }
        return safe;
    }

    /** 展示名：原始名优先（节里给用户/agent 看），空则 attachmentId。 */
    private static String displayName(AttachmentContentResolver.ResolvedAttachment r) {
        return r.originalName() != null && !r.originalName().isBlank()
                ? r.originalName() : r.attachmentId();
    }

    private static String extOf(String contentType) {
        return EXT_BY_MIME.getOrDefault(
                contentType != null ? contentType.toLowerCase(java.util.Locale.ROOT) : "", ".bin");
    }
}
