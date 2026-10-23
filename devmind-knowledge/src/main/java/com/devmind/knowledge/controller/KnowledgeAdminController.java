package com.devmind.knowledge.controller;

import com.devmind.knowledge.dto.EnrichConfigView;
import com.devmind.knowledge.dto.EnrichEntryView;
import com.devmind.knowledge.dto.EnrichSubmitView;
import com.devmind.knowledge.dto.KnowledgeSyncSourceRequest;
import com.devmind.knowledge.dto.KnowledgeSyncSourceView;
import com.devmind.knowledge.dto.SyncSubmitView;
import com.devmind.knowledge.enrich.KnowledgeEnrichService;
import com.devmind.knowledge.governance.DuplicateDetector;
import com.devmind.knowledge.governance.DuplicateReport;
import com.devmind.knowledge.governance.EvalReport;
import com.devmind.knowledge.governance.KnowledgeEvalService;
import com.devmind.knowledge.governance.LifecycleReport;
import com.devmind.knowledge.governance.LifecycleService;
import com.devmind.knowledge.sync.KnowledgeSyncService;
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

/**
 * CAP-85：知识治理 ADMIN 接口（SecurityConfig：/api/knowledge/admin/** 仅 ADMIN）。
 * 同步源管理/运行、自动增强、生命周期/重复/评测接口挂在同一控制器。
 */
@RestController
@RequestMapping("/api/knowledge/admin")
public class KnowledgeAdminController {

    private final KnowledgeSyncService syncService;
    private final KnowledgeEnrichService enrichService;
    private final LifecycleService lifecycleService;
    private final DuplicateDetector duplicateDetector;
    private final KnowledgeEvalService evalService;

    public KnowledgeAdminController(KnowledgeSyncService syncService,
                                    KnowledgeEnrichService enrichService,
                                    LifecycleService lifecycleService,
                                    DuplicateDetector duplicateDetector,
                                    KnowledgeEvalService evalService) {
        this.syncService = syncService;
        this.enrichService = enrichService;
        this.lifecycleService = lifecycleService;
        this.duplicateDetector = duplicateDetector;
        this.evalService = evalService;
    }

    // ---------------- CAP-85 FR-27：同步源 ----------------

    @GetMapping("/sync-sources")
    public List<KnowledgeSyncSourceView> listSyncSources(@RequestParam(required = false) Long kbId) {
        return syncService.list(kbId);
    }

    @PostMapping("/sync-sources")
    public KnowledgeSyncSourceView createSyncSource(@RequestBody KnowledgeSyncSourceRequest req) {
        return syncService.create(req);
    }

    @PutMapping("/sync-sources/{id}")
    public KnowledgeSyncSourceView updateSyncSource(@PathVariable Long id,
                                                    @RequestBody KnowledgeSyncSourceRequest req) {
        return syncService.update(id, req);
    }

    @DeleteMapping("/sync-sources/{id}")
    public void deleteSyncSource(@PathVariable Long id) {
        syncService.delete(id);
    }

    @PostMapping("/sync-sources/{id}/run")
    public SyncSubmitView runSyncSource(@PathVariable Long id) {
        return syncService.submit(id);
    }

    // ---------------- CAP-85 FR-28：自动增强 ----------------

    @GetMapping("/enrich/config")
    public EnrichConfigView enrichConfig() {
        return enrichService.config();
    }

    @PostMapping("/enrich")
    public EnrichSubmitView runEnrich(@RequestBody(required = false) EnrichRequest req) {
        return enrichService.submit(req == null ? null : req.kbId(), req == null ? null : req.limit());
    }

    @PostMapping("/entries/{id}/enrich")
    public EnrichSubmitView runEntryEnrich(@PathVariable Long id) {
        return enrichService.submitEntry(id);
    }

    /** 治理页增强状态清单（默认最近 100 条，不含正文）。 */
    @GetMapping("/enrich/entries")
    public List<EnrichEntryView> enrichEntries(@RequestParam(required = false) Long kbId,
                                               @RequestParam(required = false) Integer limit) {
        return enrichService.entries(kbId, limit);
    }

    /** 增强任务入参：kbId 空 = 全部知识库；limit 空 = 配置的 batch-limit。 */
    public record EnrichRequest(Long kbId, Integer limit) {
    }

    // ---------------- CAP-85 FR-29：生命周期与近重复 ----------------

    @GetMapping("/governance/lifecycle")
    public LifecycleReport lifecycle(@RequestParam(required = false) Integer expiringDays,
                                     @RequestParam(required = false) Integer staleDays,
                                     @RequestParam(required = false) Integer limit) {
        return lifecycleService.report(expiringDays, staleDays, limit);
    }

    @GetMapping("/governance/duplicates")
    public DuplicateReport duplicates(@RequestParam(required = false) Integer threshold,
                                      @RequestParam(required = false) Integer maxEntries) {
        return duplicateDetector.report(threshold, maxEntries);
    }

    // ---------------- CAP-85 FR-30：检索评测 ----------------

    @GetMapping("/eval/report")
    public EvalReport evalReport(@RequestParam(required = false) Integer topK,
                                 @RequestParam(required = false) Long kbId) {
        return evalService.report(topK, kbId);
    }
}
