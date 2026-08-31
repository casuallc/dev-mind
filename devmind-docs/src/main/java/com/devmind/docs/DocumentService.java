package com.devmind.docs;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.docs.config.DocsProperties;
import com.devmind.docs.dto.DiffView;
import com.devmind.docs.dto.DocDetail;
import com.devmind.docs.dto.DocRequest;
import com.devmind.docs.dto.DocView;
import com.devmind.docs.dto.DocVersionView;
import com.devmind.docs.dto.DocViews;
import com.devmind.docs.dto.SaveVersionRequest;
import com.devmind.docs.dto.TemplateView;
import com.devmind.docs.model.DocumentEntity;
import com.devmind.docs.model.DocumentVersionEntity;
import com.devmind.docs.repo.DocumentRepository;
import com.devmind.docs.repo.DocumentVersionRepository;
import com.devmind.docs.store.DocPaths;
import com.devmind.docs.store.DocStore;
import com.devmind.docs.store.TextDiff;
import com.devmind.project.ProjectService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档管理核心（CAP-03）：文档 CRUD + 版本化 + 状态机 + git 同步 + 检索 + 模板。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final Set<String> KINDS = Set.of("requirement", "design", "api-suite", "report");
    private static final Set<String> ACTIONS = Set.of("submit", "freeze", "unfreeze");

    private final DocumentRepository docRepo;
    private final DocumentVersionRepository verRepo;
    private final ProjectService projectService;
    private final DocStore store;
    private final DocsProperties props;

    public DocumentService(DocumentRepository docRepo,
                           DocumentVersionRepository verRepo,
                           ProjectService projectService,
                           DocStore store,
                           DocsProperties props) {
        this.docRepo = docRepo;
        this.verRepo = verRepo;
        this.projectService = projectService;
        this.store = store;
        this.props = props;
    }

    // ---------------- 列表 / 详情 ----------------

    public List<DocView> list(String kind, String projectId, String status) {
        List<DocumentEntity> list;
        if (kind != null && !kind.isBlank()) {
            list = docRepo.findByKindOrderByUpdatedAtDesc(kind);
        } else if (projectId != null && !projectId.isBlank()) {
            list = docRepo.findByProjectIdOrderByUpdatedAtDesc(projectId);
        } else if (status != null && !status.isBlank()) {
            list = docRepo.findByStatusOrderByUpdatedAtDesc(status);
        } else {
            list = docRepo.findAllByOrderByUpdatedAtDesc();
        }
        return list.stream().map(e -> DocViews.doc(e, DocPaths.filePath(e))).toList();
    }

    public DocDetail get(Long id, Integer version) {
        DocumentEntity e = requireDoc(id);
        DocumentVersionEntity v = version == null
                ? requireLatest(id)
                : verRepo.findByDocumentIdAndVersionNo(id, version)
                        .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "版本不存在: v" + version));
        return DocViews.detail(e, v, DocPaths.filePath(e));
    }

    public List<DocVersionView> versions(Long id) {
        requireDoc(id);
        return verRepo.findByDocumentIdOrderByVersionNoDesc(id).stream()
                .map(DocViews::version).toList();
    }

    // ---------------- 创建 / 保存版本 ----------------

    @Transactional
    public DocDetail create(DocRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "文档标题必填");
        }
        String kind = req.kind() == null || req.kind().isBlank() ? "requirement" : req.kind();
        if (!KINDS.contains(kind)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "kind 必须是 requirement/design/api-suite/report");
        }
        if (req.projectId() != null && !req.projectId().isBlank()) {
            projectService.requireProject(req.projectId()); // 存在性校验（FR-01 归属）
        }
        String content = resolveContent(req);
        if (content == null || content.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "文档内容必填");
        }

        DocumentEntity e = new DocumentEntity();
        e.setKind(kind);
        e.setRequirementId(blankToNull(req.requirementId()));
        e.setProjectId(blankToNull(req.projectId()));
        e.setTitle(req.title().strip());
        e.setCurrentVersion(1);
        e.setStatus("draft");
        e.setTags(DocViews.joinTags(req.tags()));
        e.setCreatedBy(actor());
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        e = docRepo.save(e);

        String sha = store.write(DocPaths.filePath(e), content, commitMsg(e, 1, "创建"));
        saveVersionRow(e, 1, content, sha, "创建");

        log.info("文档已创建: id={} kind={} title={}", e.getId(), kind, e.getTitle());
        return get(e.getId(), 1);
    }

    @Transactional
    public DocDetail saveVersion(Long id, SaveVersionRequest req) {
        DocumentEntity e = requireDoc(id);
        String content = req.contentMd();
        if (content == null || content.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "内容必填");
        }
        boolean frozen = "frozen".equals(e.getStatus());
        if (frozen && (req.changeNote() == null || req.changeNote().isBlank())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "文档已冻结，保存必须填写变更说明（FR-04）");
        }
        int newVer = e.getCurrentVersion() + 1;
        String note = req.changeNote() == null ? "" : req.changeNote().strip();
        String sha = store.write(DocPaths.filePath(e), content, commitMsg(e, newVer, note.isBlank() ? "更新" : note));
        saveVersionRow(e, newVer, content, sha, note);
        e.setCurrentVersion(newVer);
        e.setUpdatedAt(Instant.now());
        docRepo.save(e);
        return get(e.getId(), newVer);
    }

    /** 回退到指定历史版本：内容回填并生成新版本（FR-02，保留历史）。 */
    @Transactional
    public DocDetail revert(Long id, int versionNo) {
        DocumentEntity e = requireDoc(id);
        DocumentVersionEntity target = verRepo.findByDocumentIdAndVersionNo(id, versionNo)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "版本不存在: v" + versionNo));
        if (target.getVersionNo() == e.getCurrentVersion()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "目标版本已是当前版本");
        }
        int newVer = e.getCurrentVersion() + 1;
        String note = "回退到 v" + versionNo;
        String sha = store.write(DocPaths.filePath(e), target.getContentMd(), commitMsg(e, newVer, note));
        saveVersionRow(e, newVer, target.getContentMd(), sha, note);
        e.setCurrentVersion(newVer);
        e.setUpdatedAt(Instant.now());
        docRepo.save(e);
        return get(e.getId(), newVer);
    }

    // ---------------- 状态机（FR-04） ----------------

    @Transactional
    public DocDetail transition(Long id, String action) {
        DocumentEntity e = requireDoc(id);
        if (!ACTIONS.contains(action)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "action 必须是 submit/freeze/unfreeze");
        }
        String from = e.getStatus();
        switch (action) {
            case "submit" -> requireStatus(e, "draft", "仅 draft 可提交确认");
            case "freeze" -> requireStatus(e, "pending_confirm", "仅 pending_confirm 可冻结");
            case "unfreeze" -> requireStatus(e, "frozen", "仅 frozen 可解除冻结");
        }
        e.setStatus(action.equals("submit") ? "pending_confirm"
                : action.equals("freeze") ? "frozen" : "draft");
        e.setUpdatedAt(Instant.now());
        docRepo.save(e);
        log.info("文档状态流转: id={} {} -> {}", id, from, e.getStatus());
        return get(id, null);
    }

    // ---------------- diff / 检索 / 删除 / push ----------------

    public DiffView diff(Long id, int versionNo) {
        DocumentEntity e = requireDoc(id);
        DocumentVersionEntity current = requireLatest(id);
        DocumentVersionEntity target = verRepo.findByDocumentIdAndVersionNo(id, versionNo)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "版本不存在: v" + versionNo));
        return TextDiff.diff(target.getContentMd(), current.getContentMd());
    }

    /** 全文检索（FR-06）：标题/内容/标签，跨当前版本正文。 */
    public List<DocView> search(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        String kw = q.strip().toLowerCase();
        List<DocView> out = new ArrayList<>();
        for (DocumentEntity e : docRepo.findAll()) {
            String title = e.getTitle() == null ? "" : e.getTitle();
            String tags = e.getTags() == null ? "" : e.getTags();
            String content = requireLatestOrNull(e.getId());
            boolean hit = title.toLowerCase().contains(kw)
                    || tags.toLowerCase().contains(kw)
                    || (content != null && content.toLowerCase().contains(kw));
            if (hit) {
                out.add(DocViews.doc(e, DocPaths.filePath(e)));
            }
        }
        out.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return out;
    }

    @Transactional
    public void delete(Long id) {
        DocumentEntity e = requireDoc(id);
        try {
            store.delete(DocPaths.filePath(e), commitMsg(e, e.getCurrentVersion(), "删除"));
        } catch (DevMindException ex) {
            log.warn("删除 git 文件失败(继续删库记录): {} err={}", id, ex.getMessage());
        }
        verRepo.deleteByDocumentId(id);
        docRepo.delete(e);
        log.info("文档已删除: id={} title={}", id, e.getTitle());
    }

    public Map<String, String> repoInfo() {
        return Map.of("repoPath", props.getRepoPath() == null ? "" : props.getRepoPath(),
                "headSha", store.headSha());
    }

    public String push() {
        return store.push();
    }

    public List<TemplateView> templates() {
        return DocTemplates.all();
    }

    // ---------------- 内部 ----------------

    private String resolveContent(DocRequest req) {
        if (req.template() != null && !req.template().isBlank()) {
            TemplateView t = DocTemplates.byKind(req.template());
            if (t != null) {
                return t.content();
            }
        }
        return req.contentMd();
    }

    private void saveVersionRow(DocumentEntity e, int verNo, String content, String sha, String note) {
        DocumentVersionEntity v = new DocumentVersionEntity();
        v.setDocumentId(e.getId());
        v.setVersionNo(verNo);
        v.setContentMd(content);
        v.setCommitSha(sha);
        v.setChangeNote(note == null ? "" : note);
        v.setCreatedBy(actor());
        v.setCreatedAt(Instant.now());
        verRepo.save(v);
    }

    private String commitMsg(DocumentEntity e, int verNo, String note) {
        return "docs: " + e.getTitle() + " v" + verNo + (note == null || note.isBlank() ? "" : " - " + note);
    }

    private DocumentEntity requireDoc(Long id) {
        return docRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "文档不存在: " + id));
    }

    private DocumentVersionEntity requireLatest(Long id) {
        return verRepo.findTopByDocumentIdOrderByVersionNoDesc(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.INTERNAL, "文档无任何版本: " + id));
    }

    private String requireLatestOrNull(Long id) {
        return verRepo.findTopByDocumentIdOrderByVersionNoDesc(id)
                .map(DocumentVersionEntity::getContentMd).orElse(null);
    }

    private void requireStatus(DocumentEntity e, String expected, String msg) {
        if (!expected.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT, msg + "（当前 " + e.getStatus() + "）");
        }
    }

    private String actor() {
        return props.getAuthor() == null || props.getAuthor().isBlank() ? "local" : props.getAuthor();
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
