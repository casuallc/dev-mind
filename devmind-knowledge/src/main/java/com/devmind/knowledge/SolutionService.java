package com.devmind.knowledge;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.knowledge.SolutionSourceProvider;
import com.devmind.docs.DocumentService;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.knowledge.dto.SolutionSaveRequest;
import com.devmind.knowledge.dto.SolutionSaveResult;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * CAP-84 FR-24：把一轮知识回答沉淀为 CAP-03 文档。
 *
 * <p>问答正文与引用快照由 chat 模块经 common SPI 提供；本类只负责组装元信息与证据附录，
 * 再走 {@link DocumentService}（版本化、状态机、项目/需求归属校验全部复用）。</p>
 */
@Service
public class SolutionService {

    private static final Logger log = LoggerFactory.getLogger(SolutionService.class);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final String DEFAULT_TAG = "知识库方案";
    private static final Set<String> KINDS = Set.of("design", "report");
    private static final int MAX_TITLE = 200;

    private final DocumentService documentService;
    private final ObjectProvider<SolutionSourceProvider> sourceProvider;

    public SolutionService(DocumentService documentService,
                           ObjectProvider<SolutionSourceProvider> sourceProvider) {
        this.documentService = documentService;
        this.sourceProvider = sourceProvider;
    }

    public SolutionSaveResult save(String chatId, SolutionSaveRequest req) {
        SolutionSourceProvider provider = sourceProvider.getIfAvailable();
        if (provider == null) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "问答模块未装配，无法沉淀方案（请启用 devmind-chat 模块）");
        }
        SolutionSourceProvider.SolutionSource source = provider.find(chatId, req == null ? null : req.turnSeq())
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "未找到可沉淀的方案回答：会话不存在、无权访问或该轮没有回答"));
        String kind = normalizeKind(req == null ? null : req.kind());
        String title = resolveTitle(req == null ? null : req.title(), source.title());
        String content = compose(source, title);
        List<String> tags = resolveTags(req == null ? null : req.tags());
        DocDetail doc = documentService.create(new DocRequest(kind,
                blankToNull(req == null ? null : req.requirementId()),
                null,
                blankToNull(req == null ? null : req.projectId()),
                title, tags, null, content));
        log.info("方案已沉淀为文档: chat={} doc={} kind={} title={}", chatId, doc.id(), kind, title);
        return new SolutionSaveResult(doc.id(), doc.versionNo(), doc.title(), doc.kind());
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return "design";
        }
        String normalized = kind.strip().toLowerCase(java.util.Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "方案只支持沉淀为 design / report 文档: " + kind);
        }
        return normalized;
    }

    private static String resolveTitle(String requested, String sessionTitle) {
        String title = requested == null ? null : requested.strip();
        if (title == null || title.isEmpty()) {
            String base = sessionTitle == null || sessionTitle.isBlank()
                    ? "知识库方案" : sessionTitle.strip();
            title = base.endsWith("方案") ? base : base + " 方案";
        }
        return title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE);
    }

    private static List<String> resolveTags(List<String> requested) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(DEFAULT_TAG);
        if (requested != null) {
            requested.stream().filter(t -> t != null && !t.isBlank()).map(String::strip)
                    .forEach(tags::add);
        }
        return new ArrayList<>(tags);
    }

    private static String compose(SolutionSourceProvider.SolutionSource source, String title) {
        Instant generatedAt = source.generatedAt() == null ? Instant.now() : source.generatedAt();
        String bases = source.knowledgeBaseNames() == null || source.knowledgeBaseNames().isEmpty()
                ? "—" : String.join("、", source.knowledgeBaseNames());
        int citations = source.evidence() == null ? 0 : source.evidence().size();
        StringBuilder sb = new StringBuilder();
        sb.append("> 生成时间：").append(TIME_FORMAT.format(generatedAt))
                .append(" ｜ 模式：").append(source.mode() == null ? "ANSWER" : source.mode())
                .append(" ｜ 模型：").append(source.model() == null || source.model().isBlank() ? "—" : source.model())
                .append(" ｜ 知识库：").append(bases)
                .append(" ｜ 引用：").append(citations).append(" 条")
                .append(" ｜ 会话：").append("[查看会话](/chats?cid=").append(source.chatId()).append(")")
                .append("\n\n");
        if (source.question() != null && !source.question().isBlank()) {
            sb.append("**问题**：").append(source.question().replace('\n', ' ').strip()).append("\n\n");
        }
        sb.append("# ").append(title).append("\n\n")
                .append(source.answer() == null ? "" : source.answer().strip()).append("\n\n")
                .append("---\n\n## 证据附录\n\n");
        if (citations == 0) {
            sb.append("本轮回答未记录引用。\n");
            return sb.toString();
        }
        for (SolutionSourceProvider.SolutionSource.Evidence evidence : source.evidence()) {
            sb.append("### [S").append(evidence.citationNo()).append("] ")
                    .append(evidence.entryName() == null || evidence.entryName().isBlank()
                            ? "知识条目" : evidence.entryName())
                    .append("\n\n");
            if (evidence.sourceUri() != null && !evidence.sourceUri().isBlank()) {
                sb.append("- 来源：").append(evidence.sourceUri()).append('\n');
            }
            if (evidence.chunkId() != null) {
                sb.append("- 分块：").append(evidence.chunkId()).append('\n');
            }
            if (evidence.score() != null) {
                sb.append("- 相关度：").append(String.format(java.util.Locale.ROOT, "%.4f", evidence.score()))
                        .append('\n');
            }
            String snippet = evidence.snippet() == null ? "" : evidence.snippet().replaceAll("\\s+", " ").strip();
            if (!snippet.isEmpty()) {
                sb.append("\n> ").append(snippet).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
