package com.devmind.knowledge.controller;

import com.devmind.common.dto.PageView;
import com.devmind.common.integration.FeishuDocFetcher.FeishuIntegration;
import com.devmind.common.knowledge.KnowledgeRetriever;
import com.devmind.knowledge.FeishuImportService;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.SolutionService;
import com.devmind.knowledge.dto.ClassifyPreviewItem;
import com.devmind.knowledge.dto.ClassifyPreviewRequest;
import com.devmind.knowledge.dto.EntryRequest;
import com.devmind.knowledge.dto.EntrySummaryView;
import com.devmind.knowledge.dto.EntryView;
import com.devmind.knowledge.dto.FeishuImportRequest;
import com.devmind.knowledge.dto.FeishuImportResult;
import com.devmind.knowledge.dto.ImportBatchView;
import com.devmind.knowledge.dto.ImportSubmitResult;
import com.devmind.knowledge.dto.KnowledgeBaseRequest;
import com.devmind.knowledge.dto.KnowledgeBaseView;
import com.devmind.knowledge.dto.KnowledgeChunkView;
import com.devmind.knowledge.dto.KnowledgeSearchResponse;
import com.devmind.knowledge.dto.KnowledgeSearchV2Request;
import com.devmind.knowledge.dto.KnowledgeSearchV2Response;
import com.devmind.knowledge.dto.KnowledgeVersionView;
import com.devmind.knowledge.dto.PreviewResult;
import com.devmind.knowledge.dto.ProposalRequest;
import com.devmind.knowledge.dto.ProposalView;
import com.devmind.knowledge.dto.ReindexResult;
import com.devmind.knowledge.dto.SolutionSaveRequest;
import com.devmind.knowledge.dto.SolutionSaveResult;
import com.devmind.knowledge.index.KnowledgeIndexService;
import com.devmind.knowledge.ingest.LocalImportService;
import com.devmind.knowledge.retrieve.KnowledgeSearchService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库 REST（CAP-04 条目/提案/预览 + CAP-44 库容器管理 + CAP-84 上传/混合检索）。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService service;
    private final KnowledgeRetriever retriever;
    private final FeishuImportService feishuImportService;
    private final LocalImportService localImportService;
    private final KnowledgeSearchService searchService;
    private final KnowledgeIndexService indexService;
    private final SolutionService solutionService;

    public KnowledgeController(KnowledgeBaseService service, KnowledgeRetriever retriever,
                               FeishuImportService feishuImportService,
                               LocalImportService localImportService,
                               KnowledgeSearchService searchService,
                               KnowledgeIndexService indexService,
                               SolutionService solutionService) {
        this.service = service;
        this.retriever = retriever;
        this.feishuImportService = feishuImportService;
        this.localImportService = localImportService;
        this.searchService = searchService;
        this.indexService = indexService;
        this.solutionService = solutionService;
    }

    // ---------------- 知识库（CAP-44 FR-07） ----------------

    @GetMapping("/bases")
    public List<KnowledgeBaseView> listBases() {
        return service.listBases();
    }

    @PostMapping("/bases")
    public KnowledgeBaseView createBase(@RequestBody KnowledgeBaseRequest req) {
        return service.createBase(req);
    }

    @GetMapping("/bases/{id}")
    public KnowledgeBaseView getBase(@PathVariable Long id) {
        return service.getBase(id);
    }

    @PutMapping("/bases/{id}")
    public KnowledgeBaseView updateBase(@PathVariable Long id, @RequestBody KnowledgeBaseRequest req) {
        return service.updateBase(id, req);
    }

    @DeleteMapping("/bases/{id}")
    public void deleteBase(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean force) {
        service.deleteBase(id, force);
    }

    @GetMapping("/bases/{id}/entries")
    public List<EntryView> listBaseEntries(@PathVariable Long id) {
        return service.listByBase(id);
    }

    /** CAP-86 FR-38 库内条目分页（轻量摘要，不含正文 LOB）。 */
    @GetMapping("/bases/{id}/entries/page")
    public PageView<EntrySummaryView> pageBaseEntries(@PathVariable Long id,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String docType,
                                                      @RequestParam(required = false) String source,
                                                      @RequestParam(required = false) String indexStatus,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return service.pageByBase(id, keyword, docType, source, indexStatus, status, page, size);
    }

    /**
     * CAP-48 FR-08 重建索引（换端点/换模型后的修复入口）。
     *
     * @param onlyMismatched true = 只重建血缘失配的条目（维度/端点对不上的那些）
     */
    @PostMapping("/bases/{id}/reindex")
    public ReindexResult reindexBase(@PathVariable Long id,
                                     @RequestParam(defaultValue = "false") boolean onlyMismatched) {
        return service.reindexBase(id, onlyMismatched);
    }

    // ---------------- 条目 ----------------

    @GetMapping("/entries")
    public List<EntryView> list(@RequestParam(required = false) String scope,
                                @RequestParam(required = false) String projectId,
                                @RequestParam(required = false) String status) {
        return service.list(scope, projectId, status);
    }

    @GetMapping("/entries/search")
    public List<EntryView> search(@RequestParam String q,
                                  @RequestParam(required = false) String projectId) {
        return service.search(q, projectId);
    }

    @GetMapping("/entries/{id}")
    public EntryView get(@PathVariable Long id) {
        return service.getEntry(id);
    }

    @PostMapping("/entries")
    public EntryView create(@RequestBody EntryRequest req) {
        return service.createEntry(req);
    }

    @PutMapping("/entries/{id}")
    public EntryView update(@PathVariable Long id, @RequestBody EntryRequest req) {
        return service.updateEntry(id, req);
    }

    @DeleteMapping("/entries/{id}")
    public void delete(@PathVariable Long id) {
        service.deleteEntry(id);
    }

    @PostMapping("/entries/{id}/reindex")
    public EntryView reindex(@PathVariable Long id) {
        return service.reindex(id);
    }

    /** CAP-85 FR-29：人工复核（任意登录用户）。 */
    @PostMapping("/entries/{id}/review")
    public EntryView review(@PathVariable Long id) {
        return service.review(id);
    }

    /** CAP-84 FR-06：文档版本列表（不含正文）与单版本正文。 */
    @GetMapping("/entries/{id}/versions")
    public List<KnowledgeVersionView> listVersions(@PathVariable Long id) {
        return service.listVersions(id);
    }

    @GetMapping("/entries/{id}/versions/{version}")
    public KnowledgeVersionView.Detail getVersion(@PathVariable Long id, @PathVariable int version) {
        return service.getVersion(id, version);
    }

    /** CAP-84 FR-15：当前索引分块列表（正文 + 标题路径 + 原文偏移），详情页「分块」页签用。 */
    @GetMapping("/entries/{id}/chunks")
    public List<KnowledgeChunkView> listChunks(@PathVariable Long id) {
        return service.listChunks(id);
    }

    // ---------------- 飞书导入（CAP-45 FR-03/FR-04） ----------------

    @GetMapping("/feishu/integrations")
    public List<FeishuIntegration> feishuIntegrations() {
        return feishuImportService.listIntegrations();
    }

    @PostMapping("/bases/{id}/import/feishu")
    public List<FeishuImportResult> importFeishu(@PathVariable Long id,
                                                 @RequestBody FeishuImportRequest req) {
        return feishuImportService.importDocs(id, req);
    }

    @PostMapping("/entries/{id}/resync")
    public FeishuImportResult resyncFeishu(@PathVariable Long id) {
        return feishuImportService.resync(id);
    }

    // ---------------- 本地上传（CAP-84 FR-02/FR-05） ----------------

    /**
     * 文件/文件夹/ZIP 上传。文件夹上传由前端把每个文件的 webkitRelativePath 放进
     * relativePaths（与 files 顺序一致）；ZIP 由服务端安全解压后逐文件入队。
     */
    @PostMapping("/bases/{id}/import/upload")
    public ImportSubmitResult upload(@PathVariable Long id,
                                     @RequestParam("files") List<MultipartFile> files,
                                     @RequestParam(required = false) List<String> relativePaths,
                                     @RequestParam(defaultValue = "TECH") String defaultDocType,
                                     @RequestParam(required = false) List<String> tags,
                                     @RequestParam(defaultValue = "system") String actor) {
        return localImportService.submit(id, files, relativePaths, defaultDocType, tags, actor);
    }

    @GetMapping("/imports")
    public List<ImportBatchView> listImports(@RequestParam(required = false) Long kbId) {
        return localImportService.list(kbId);
    }

    @GetMapping("/imports/{batchId}")
    public ImportBatchView getImport(@PathVariable String batchId) {
        return localImportService.getBatch(batchId);
    }

    @PostMapping("/imports/{batchId}/retry")
    public ImportBatchView retryImport(@PathVariable String batchId) {
        return localImportService.retry(batchId);
    }

    /** 上传前分类预览：路径 → 预估类型/标签（与入库规则一致，不落库）。 */
    @PostMapping("/imports/classify-preview")
    public List<ClassifyPreviewItem> classifyPreview(@RequestBody ClassifyPreviewRequest req) {
        return localImportService.previewClassification(req.relativePaths(), req.defaultDocType());
    }

    /** 单文件重试：其余文件保持现状，仅重置该文件后重跑批次（已成功文件自动跳过）。 */
    @PostMapping("/imports/{batchId}/files/{fileId}/retry")
    public ImportBatchView retryImportFile(@PathVariable String batchId, @PathVariable Long fileId) {
        return localImportService.retryFile(batchId, fileId);
    }

    // ---------------- 索引健康度（CAP-84 FR-15） ----------------

    @GetMapping("/index/health")
    public KnowledgeIndexService.IndexHealth indexHealth() {
        return indexService.health();
    }

    /** CAP-84 M2：全量重建（异步任务）。 */
    @PostMapping("/admin/rebuild")
    public KnowledgeBaseService.RebuildSubmit adminRebuild() {
        return service.submitRebuildAll();
    }

    /** CAP-84 M2：索引统计（文档/分块数、体积、schema 版本、维度分布）。 */
    @GetMapping("/admin/index/stats")
    public KnowledgeIndexService.AdminIndexStats adminIndexStats() {
        return indexService.adminStats();
    }

    // ---------------- 检索（CAP-44 FR-06） ----------------

    @PostMapping("/search")
    public KnowledgeSearchResponse searchChunks(
            @RequestBody KnowledgeSearchResponse.KnowledgeSearchRequest req) {
        KnowledgeRetriever.Detailed result =
                retriever.retrieveDetailed(req.kbIds(), req.query(), req.topK() == null ? 0 : req.topK());
        return new KnowledgeSearchResponse(result.vector(), result.chunks(), result.degradedReason());
    }

    /** CAP-84 FR-13 全局混合检索：BM25 + 向量 RRF、文档级聚合、高亮片段。 */
    @PostMapping("/search/v2")
    public KnowledgeSearchV2Response searchV2(@RequestBody KnowledgeSearchV2Request req) {
        return searchService.searchDocuments(req);
    }

    /** CAP-84 FR-24 一键沉淀：取指定轮次方案正文，创建 CAP-03 文档（含证据附录）。 */
    @PostMapping("/solutions/{chatId}/save")
    public SolutionSaveResult saveSolution(@PathVariable String chatId,
                                           @RequestBody(required = false) SolutionSaveRequest req) {
        return solutionService.save(chatId, req);
    }

    // ---------------- 注入预览（FR-04） ----------------

    @GetMapping("/preview")
    public PreviewResult preview(@RequestParam(required = false) String projectId,
                                 @RequestParam(required = false) String taskSpec) {
        return service.preview(projectId, taskSpec);
    }

    // ---------------- 提案（FR-05/FR-06） ----------------

    @GetMapping("/proposals")
    public List<ProposalView> listProposals(@RequestParam(required = false) String status) {
        return service.listProposals(status);
    }

    @PostMapping("/proposals")
    public ProposalView createProposal(@RequestBody ProposalRequest req) {
        return service.createProposal(req);
    }

    @PostMapping("/proposals/{id}/adopt")
    public ProposalView adopt(@PathVariable Long id,
                              @RequestParam String target,
                              @RequestParam(required = false) String projectId) {
        return service.adopt(id, target, projectId);
    }

    @PostMapping("/proposals/{id}/reject")
    public ProposalView reject(@PathVariable Long id) {
        return service.reject(id);
    }

    @DeleteMapping("/proposals/{id}")
    public void deleteProposal(@PathVariable Long id) {
        service.deleteProposal(id);
    }
}
