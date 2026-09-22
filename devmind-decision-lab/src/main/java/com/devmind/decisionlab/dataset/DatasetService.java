package com.devmind.decisionlab.dataset;

import com.devmind.auth.IdentityService;
import com.devmind.common.decision.TriageQuestions;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decision.record.model.DecisionRecordEntity;
import com.devmind.decision.record.repo.DecisionRecordRepository;
import com.devmind.decisionlab.dataset.dto.DatasetDetail;
import com.devmind.decisionlab.dataset.dto.DatasetItemDetail;
import com.devmind.decisionlab.dataset.dto.DatasetItemRequest;
import com.devmind.decisionlab.dataset.dto.DatasetItemView;
import com.devmind.decisionlab.dataset.dto.DatasetRequest;
import com.devmind.decisionlab.dataset.dto.DatasetView;
import com.devmind.decisionlab.dataset.dto.DatasetViews;
import com.devmind.decisionlab.dataset.dto.RecordsIntakeRequest;
import com.devmind.decisionlab.dataset.dto.RecordsIntakeResult;
import com.devmind.decisionlab.dataset.dto.RecordsPreview;
import com.devmind.decisionlab.dataset.model.DecisionDatasetEntity;
import com.devmind.decisionlab.dataset.model.DecisionDatasetItemEntity;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetItemRepository;
import com.devmind.decisionlab.dataset.repo.DecisionDatasetRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-56 FR-02 评测集与样本：建集 → 标注 → 冻结（改 → 修订为新版本）。
 *
 * <p><b>为什么"改"要变成"新版本"</b>：指标只有在输入固定时才可比。允许就地改条目的话，
 * 上周跑的报告与这周跑的会指着同一个 dataset_id 却测着两份不同的东西，历史数字全部失真且无声。
 * 所以草稿可改、冻结只读，要改就复制成 v+1——旧版本连同它跑出来的报告一起留着。</p>
 *
 * <p><b>冻结是唯一把"红线"变成机械保证的地方</b>：既查对照组齐不齐（有没有那三类），
 * 也查每组是不是<b>真的</b>是那个组（{@link CaseGroups#holds} 逐条读 state 对内容），
 * 还查题面版本是否唯一。这些检查故意都放在冻结而不是"随时提醒"：草稿期标注本来就是渐进的，
 * 中途缺对照组再正常不过；但一旦要变成"指标的分母"，就必须是齐的。</p>
 *
 * <p>事务口径：本类的写操作都是短事务（一组 JSON 的增删改、一次冻结）。评测/微调的
 * <b>异步触发</b>不在这里——那是 CAP-56 FR-03/FR-05 的事，且按项目红线禁 {@code @Transactional}。</p>
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    /** FR-02 的起步规模（60-100）。低于它只是提醒，不阻断——回流集天然可能更小 */
    static final int SUGGESTED_BENCHMARK_ITEMS = 60;

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_NAME_CHARS = 128;
    private static final int MAX_NOTE_CHARS = 512;

    private final DecisionDatasetRepository repo;
    private final DecisionDatasetItemRepository itemRepo;
    private final DecisionRecordRepository recordRepo;
    private final DatasetJson json;
    private final IdentityService identityService;

    public DatasetService(DecisionDatasetRepository repo, DecisionDatasetItemRepository itemRepo,
                          DecisionRecordRepository recordRepo, DatasetJson json,
                          IdentityService identityService) {
        this.repo = repo;
        this.itemRepo = itemRepo;
        this.recordRepo = recordRepo;
        this.json = json;
        this.identityService = identityService;
    }

    // ---------------- 建集 ----------------

    public DatasetDetail create(DatasetRequest req) {
        String name = requireName(req);
        String kind = requireKind(req);
        if (!repo.findByNameOrderByVersionDesc(name).isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "已有同名评测集「" + name + "」——同一个集要改内容请用「修订为新版本」，"
                            + "另起新名字才是另一个集");
        }
        DecisionDatasetEntity e = new DecisionDatasetEntity();
        e.setName(name);
        e.setKind(kind);
        e.setVersion(1);
        e.setFrozen(Boolean.FALSE);
        e.setItemCount(0);
        e.setNote(trimTo(req.note(), MAX_NOTE_CHARS));
        e.setCreatedBy(identityService.currentActor());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(e.getCreatedAt());
        DecisionDatasetEntity saved = repo.save(e);
        log.info("评测集创建: id={} name={} kind={} by={}", saved.getId(), name, kind, saved.getCreatedBy());
        return detail(saved.getId());
    }

    // ---------------- 查 ----------------

    public PageView<DatasetView> list(String kind, int page, int size) {
        int p = Math.max(page, 0);
        int s = pageSize(size);
        Page<DecisionDatasetEntity> result = repo.search(blankToNull(kind), PageRequest.of(p, s));
        return new PageView<>(result.getContent().stream().map(DatasetViews::of).toList(),
                result.getTotalElements(), p, s);
    }

    public DatasetDetail detail(Long id) {
        DecisionDatasetEntity e = requireDataset(id);
        List<DecisionDatasetItemEntity> rows = itemRepo.findByDatasetIdOrderByIdAsc(e.getId());
        // 冻结后不再给提醒：它已经通过了当时的校验，拿今天的口径去"提醒"过去的集只会误导
        List<String> warnings = e.isFrozen() ? List.of() : freezeWarnings(e, rows);
        return new DatasetDetail(DatasetViews.of(e), caseGroupCounts(rows), coverage(rows), warnings,
                manifestOf(e));
    }

    public List<CaseGroupTemplates.Template> templates() {
        return CaseGroupTemplates.all();
    }

    // ---------------- 改 / 删（仅草稿） ----------------

    public DatasetDetail update(Long id, DatasetRequest req) {
        DecisionDatasetEntity e = requireDraft(id);
        String name = requireName(req);
        if (!name.equals(e.getName())) {
            // 改名 = 换一个集（修订链按名字串起来），不允许——否则 v1 叫 A、v2 叫 B，历史难追
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "评测集名不可修改（修订链按名字串联）——要另起名字请新建一个评测集");
        }
        e.setKind(requireKind(req));
        e.setNote(trimTo(req.note(), MAX_NOTE_CHARS));
        e.setUpdatedAt(Instant.now());
        repo.save(e);
        return detail(e.getId());
    }

    /** 删只允许草稿：冻结集可能已被评测引用（报告要指得回去），要放弃它请先确认没有报告在用 */
    public void delete(Long id) {
        DecisionDatasetEntity e = requireDraft(id);
        itemRepo.deleteByDatasetId(e.getId());
        repo.delete(e);
        log.info("评测集删除: id={} name={} v{}", e.getId(), e.getName(), e.getVersion());
    }

    // ---------------- 样本 ----------------

    public PageView<DatasetItemView> items(Long id, String caseGroup, int page, int size) {
        requireDataset(id);
        int p = Math.max(page, 0);
        int s = pageSize(size);
        String group = blankToNull(caseGroup);
        Page<DecisionDatasetItemEntity> result = group == null
                ? itemRepo.findByDatasetIdOrderByIdAsc(id, PageRequest.of(p, s))
                : itemRepo.findByDatasetIdAndCaseGroupOrderByIdAsc(id, group, PageRequest.of(p, s));
        return new PageView<>(result.getContent().stream().map(row -> DatasetViews.item(row, json)).toList(),
                result.getTotalElements(), p, s);
    }

    public DatasetItemDetail item(Long id, Long itemId) {
        return DatasetViews.detail(requireItem(id, itemId), json);
    }

    /** 加一条样本（草稿专用）。校验在 {@link #validate} 里，落库前就把不合法的挡掉 */
    @Transactional
    public DatasetItemDetail addItem(Long id, DatasetItemRequest req) {
        DecisionDatasetEntity dataset = requireDraft(id);
        DecisionDatasetItemEntity e = new DecisionDatasetItemEntity();
        e.setDatasetId(dataset.getId());
        e.setSource(DecisionDatasetItemEntity.SOURCE_MANUAL);
        e.setCreatedAt(Instant.now());
        DecisionDatasetItemEntity saved = saveValidated(e, req);
        refreshCount(dataset);
        return DatasetViews.detail(saved, json);
    }

    /** 替换一条样本：整条覆盖（不做字段级 patch——三份 JSON 是一体的，改一半没有意义） */
    @Transactional
    public DatasetItemDetail replaceItem(Long id, Long itemId, DatasetItemRequest req) {
        DecisionDatasetEntity dataset = requireDraft(id);
        DecisionDatasetItemEntity e = requireItem(id, itemId);
        DecisionDatasetItemEntity saved = saveValidated(e, req);
        refreshCount(dataset);
        return DatasetViews.detail(saved, json);
    }

    @Transactional
    public void deleteItem(Long id, Long itemId) {
        DecisionDatasetEntity dataset = requireDraft(id);
        itemRepo.delete(requireItem(id, itemId));
        refreshCount(dataset);
    }

    /** 校验 + 落库（加/改/收编共用）；<b>不</b>刷条数——批量收编要在最后统一刷一次，不必逐条往返 */
    private DecisionDatasetItemEntity saveValidated(DecisionDatasetItemEntity e, DatasetItemRequest req) {
        validate(e, req);
        return itemRepo.save(e);
    }

    // ---------------- 冻结 / 修订 ----------------

    /**
     * 冻结：把这份集定格成"指标的分母"。校验三条——非空、对照组齐且名副其实、题面版本唯一。
     *
     * @throws DevMindException 409（状态/内容不满足冻结条件，重试同一个请求也没用，得先改数据）
     */
    @Transactional
    public DatasetDetail freeze(Long id) {
        DecisionDatasetEntity e = requireDataset(id);
        if (e.isFrozen()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "评测集「" + e.getName() + "」v" + e.getVersion() + " 已冻结（冻结后只读）");
        }
        List<DecisionDatasetItemEntity> rows = itemRepo.findByDatasetIdOrderByIdAsc(e.getId());
        if (rows.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT, "评测集为空，没有可评测的样本");
        }
        List<String> blockers = new ArrayList<>(freezeBlockers(e, rows));
        if (!blockers.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT, "评测集还不能冻结：" + String.join("；", blockers));
        }
        String questionSetVersion = singleQuestionSetVersion(rows).orElseThrow();
        e.setFrozen(Boolean.TRUE);
        e.setQuestionSetVersion(questionSetVersion);
        e.setFrozenBy(identityService.currentActor());
        e.setFrozenAt(Instant.now());
        e.setUpdatedAt(e.getFrozenAt());
        e.setItemCount(rows.size());
        e.setFreezeManifest(json.write(manifest(e, rows)));
        repo.save(e);
        log.info("评测集冻结: id={} name={} v{} 条数={} 题面={} by={}", e.getId(), e.getName(), e.getVersion(),
                rows.size(), questionSetVersion, e.getFrozenBy());
        return detail(e.getId());
    }

    /**
     * 修订为新版本：复制成同名 v+1 的草稿，条目原样搬过来。冻结集因此永远可追、可改、可再评。
     *
     * @throws DevMindException 409（草稿不用修版——直接改就行）
     */
    @Transactional
    public DatasetDetail revise(Long id) {
        DecisionDatasetEntity old = requireDataset(id);
        if (!old.isFrozen()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "评测集「" + old.getName() + "」v" + old.getVersion() + " 尚未冻结，直接改即可");
        }
        List<DecisionDatasetItemEntity> rows = itemRepo.findByDatasetIdOrderByIdAsc(old.getId());
        DecisionDatasetEntity fresh = new DecisionDatasetEntity();
        fresh.setName(old.getName());
        fresh.setKind(old.getKind());
        fresh.setVersion(old.getVersion() + 1);
        fresh.setFrozen(Boolean.FALSE);
        fresh.setNote(trimTo("（自 v" + old.getVersion() + " 修订）" + (old.getNote() == null ? "" : old.getNote()),
                MAX_NOTE_CHARS));
        fresh.setCreatedBy(identityService.currentActor());
        fresh.setCreatedAt(Instant.now());
        fresh.setUpdatedAt(fresh.getCreatedAt());
        fresh.setItemCount(0);
        DecisionDatasetEntity saved = repo.save(fresh);
        for (DecisionDatasetItemEntity row : rows) {
            itemRepo.save(copyOf(row, saved.getId()));
        }
        saved.setItemCount(rows.size());
        repo.save(saved);
        log.info("评测集修版: {} v{} → v{}（{} 条）by={}", saved.getName(), old.getVersion(), saved.getVersion(),
                rows.size(), saved.getCreatedBy());
        return detail(saved.getId());
    }

    // ---------------- 从决策记录收编（FR-02 回流） ----------------

    /**
     * 收编预览：动手之前先说清"能收多少、收不了的为什么"。
     *
     * <p>预览与收编<b>共用同一套判定</b>（{@link RecordIntake} + {@link #judge}），所以预览不是估算，
     * 而是"照这样收会发生什么"。两者若各写一份，用户就会遇到"预览说 120 条、收完只有 3 条"
     * 这种事——预告与实际不符比没有预告更糟。</p>
     *
     * @param sampleLimit 逐条样例最多给几条（前端列表用）
     */
    public RecordsPreview previewFromRecords(Long id, String capability, String since, int sampleLimit) {
        DecisionDatasetEntity dataset = requireIntakeTarget(id);
        Instant from = parseSince(since);
        Page<DecisionRecordEntity> page = recordRepo.findForIntake(blankToNull(capability), from,
                PageRequest.of(0, RecordIntake.MAX_SCAN));
        List<Judgement> judgements = judge(page.getContent(), collectedRecords(dataset.getId()));
        int collectable = (int) judgements.stream().filter(Judgement::collectable).count();
        return new RecordsPreview(blankToNull(capability), blankToNull(since), page.getTotalElements(),
                judgements.size(), page.getTotalElements() > judgements.size(), collectable,
                reasonCounts(judgements), groupCounts(judgements),
                samples(judgements, Math.max(sampleLimit, 0)));
    }

    /**
     * 收编：把范围内的决策记录收成样本。能收的收，不能收的逐条说清为什么不收。
     *
     * <p><b>可重复执行</b>：已收编过的记录按 {@code origin_record_id} 认出来并跳过，
     * 所以"今天收一遍、明天再收一遍"只会补上新的那些，不会把旧样本翻倍
     * （翻倍会直接污染指标的分母，而且看着像样本变多了）。</p>
     *
     * <p>事务：整批一次提交。中途失败时要么全收进来、要么一条没进——虽然靠去重重跑也不会坏，
     * 但"收了 37 条"这种半截状态在报告里太难解释。</p>
     */
    @Transactional
    public RecordsIntakeResult collectFromRecords(Long id, RecordsIntakeRequest req) {
        DecisionDatasetEntity dataset = requireIntakeTarget(id);
        Instant from = parseSince(req == null ? null : req.since());
        Page<DecisionRecordEntity> page = recordRepo.findForIntake(
                blankToNull(req == null ? null : req.capability()), from,
                PageRequest.of(0, RecordIntake.MAX_SCAN));
        List<Judgement> judgements = judge(page.getContent(), collectedRecords(dataset.getId()));
        int added = 0;
        for (Judgement j : judgements) {
            if (!j.collectable()) {
                continue;
            }
            DecisionDatasetItemEntity e = new DecisionDatasetItemEntity();
            e.setDatasetId(dataset.getId());
            e.setSource(DecisionDatasetItemEntity.SOURCE_RECORD);
            e.setOriginRecordId(j.row().getId());
            e.setCreatedAt(Instant.now());
            saveValidated(e, intakeRequest(j));
            added++;
        }
        if (added > 0) {
            refreshCount(dataset);
        }
        Map<String, Long> skipped = reasonCounts(judgements);
        skipped.remove(null); // 可收编的那些不在"跳过原因"里
        log.info("决策记录收编: dataset={} v{} capability={} 命中={} 新增={} 跳过={}",
                dataset.getName(), dataset.getVersion(), req == null ? null : req.capability(),
                page.getTotalElements(), added, judgements.size() - added);
        return new RecordsIntakeResult(added, judgements.size() - added, skipped, detail(dataset.getId()));
    }

    /**
     * 收编目标必须是<b>草稿状态的 REPLAY 集</b>。
     *
     * <p>为什么认 kind：{@code BENCHMARK} 是对"这份基准是人一题一题标出来的"的声明。
     * 把回流的样本倒进一个基准集，这份基准的可信度就再也说不清了（谁标的？标的什么？）。
     * 而 REPLAY 集里本来就可以手工补样本（{@link #addItem} 不看 kind），所以这条限制
     * 只是不许把回流集<b>叫成</b>基准集，不影响任何实际做法。</p>
     */
    private DecisionDatasetEntity requireIntakeTarget(Long id) {
        DecisionDatasetEntity e = requireDraft(id);
        if (!DecisionDatasetEntity.KIND_REPLAY.equals(e.getKind())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "「从决策记录收编」只能收进 " + DecisionDatasetEntity.KIND_REPLAY
                            + " 集（「" + e.getName() + "」是 " + e.getKind()
                            + "）——基准集是人一题一题标出来的，混进回流样本后它的可信度就说不清了；"
                            + "要收编请新建一个 " + DecisionDatasetEntity.KIND_REPLAY + " 集");
        }
        return e;
    }

    /** 范围内逐条判定（已收编的在这里一并落定，因为它要查库而 {@link RecordIntake} 是纯函数） */
    private List<Judgement> judge(List<DecisionRecordEntity> rows, Set<Long> collected) {
        List<Judgement> out = new ArrayList<>(rows.size());
        for (DecisionRecordEntity row : rows) {
            if (collected.contains(row.getId())) {
                out.add(new Judgement(row, RecordIntake.Disposition.skip(RecordIntake.REASON_COLLECTED, null)));
                continue;
            }
            out.add(new Judgement(row, RecordIntake.classify(json.parse(row.getStateJson()),
                    json.parseQuestions(row.getQuestionsJson()), json.parse(row.getGoldJson()))));
        }
        return out;
    }

    /** 收编时的落库请求：题面走标准题面（判定时已验过逐项相等），gold 用记录里的人工原值 */
    private static DatasetItemRequest intakeRequest(Judgement j) {
        DecisionRecordEntity row = j.row();
        return new DatasetItemRequest(j.disposition().state(), null, j.disposition().gold(),
                j.caseGroup(), provenance(row));
    }

    /** 样本上留一段人能读的来源说明（持久的追溯靠 origin_record_id，这段是让人不必回查就能看懂） */
    private static String provenance(DecisionRecordEntity row) {
        StringBuilder note = new StringBuilder("回流自决策记录 #").append(row.getId());
        if (row.getHumanAction() != null && !row.getHumanAction().isBlank()) {
            note.append("（人工动作 ").append(row.getHumanAction());
            if (row.getDecidedBy() != null && !row.getDecidedBy().isBlank()) {
                note.append(" · ").append(row.getDecidedBy());
            }
            note.append("）");
        }
        return note.toString();
    }

    private Set<Long> collectedRecords(Long datasetId) {
        return new HashSet<>(itemRepo.collectedRecordIds(datasetId));
    }

    /** 原因码 → 条数；四个原因都出现（缺的记 0），可收编的记在 null 键上（调用方自行移除） */
    private static Map<String, Long> reasonCounts(List<Judgement> judgements) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String reason : RecordIntake.REASON_ORDER) {
            counts.put(reason, 0L);
        }
        for (Judgement j : judgements) {
            counts.merge(j.reasonCode(), 1L, Long::sum);
        }
        return counts;
    }

    /** 可收编的样本会进哪些组（含按内容自动识别的对照组），与 {@link #caseGroupCounts} 同口径 */
    private static Map<String, Long> groupCounts(List<Judgement> judgements) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String group : CaseGroups.ALL) {
            counts.put(group, 0L);
        }
        for (Judgement j : judgements) {
            if (j.collectable()) {
                counts.merge(j.caseGroup(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /**
     * 逐条样例：<b>先可收编的</b>（那是马上要写进去的东西，最该先核对），再补几条被留下的
     * （让人看清筛出来的范围对不对——只看能收的容易误以为"就这几条"）。
     */
    private List<RecordsPreview.Candidate> samples(List<Judgement> judgements, int limit) {
        List<RecordsPreview.Candidate> out = new ArrayList<>();
        int quota = limit;
        for (Judgement j : judgements) {
            if (quota > 0 && j.collectable()) {
                out.add(candidate(j));
                quota--;
            }
        }
        for (Judgement j : judgements) {
            if (quota > 0 && !j.collectable()) {
                out.add(candidate(j));
                quota--;
            }
        }
        return out;
    }

    private RecordsPreview.Candidate candidate(Judgement j) {
        DecisionRecordEntity row = j.row();
        return new RecordsPreview.Candidate(row.getId(), row.getCapability(), row.getSubjectId(),
                DatasetViews.title(json.parse(row.getStateJson())), row.getHumanAction(),
                j.caseGroup(), CaseGroups.label(j.caseGroup()),
                j.collectable() ? "COLLECT" : "SKIP",
                j.reasonCode(), j.collectable() ? null : RecordIntake.label(j.reasonCode()),
                j.disposition().detail());
    }

    /** yyyy-MM-dd（本机时区当天 00:00 起，与记录页的日期筛选同口径）→ Instant；空 = 不限时间 */
    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(since.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "since 期望 yyyy-MM-dd，收到「" + since + "」");
        }
    }

    /** 一条记录的最终判定（{@link RecordIntake} 的内容判断 + "是否已收编"的库判断） */
    private record Judgement(DecisionRecordEntity row, RecordIntake.Disposition disposition) {

        boolean collectable() {
            return disposition.collectable();
        }

        String reasonCode() {
            return disposition.reasonCode();
        }

        /** 可收编时才有值；跳过时按普通样本报（它不会进集，组别只是展示用） */
        String caseGroup() {
            return disposition.caseGroup() == null ? CaseGroups.NORMAL : disposition.caseGroup();
        }
    }

    // ---------------- 内部：校验 ----------------

    /**
     * 冻结前还差什么（空 = 可以冻）。返回的是给人看的一句话，逐条能对上界面。
     *
     * <p>三个检查各有各的失败方式，所以分开报而不是合成一句"评测集不合法"：
     * 缺对照组是<b>标注没做完</b>，对照组名不副实是<b>标错了</b>，题面版本混着是<b>代码换过题面</b>——
     * 三种情形的处置完全不同，混成一句话等于让人自己猜。</p>
     */
    private List<String> freezeBlockers(DecisionDatasetEntity dataset, List<DecisionDatasetItemEntity> rows) {
        List<String> blockers = new ArrayList<>();

        // ① 对照组齐不齐（FR-02 红线）
        Map<String, Long> counts = caseGroupCounts(rows);
        List<String> missing = CaseGroups.CONTROL.stream().filter(g -> counts.getOrDefault(g, 0L) == 0).toList();
        if (!missing.isEmpty()) {
            blockers.add("缺少对照组：" + missing.stream().map(CaseGroups::label).toList()
                    + "（FR-02 要求三类对照组缺一不可——没有它们，「恒答重复」这类退化看不出来）");
        }

        // ② 对照组名副其实吗（标签与 state 内容对不上的一律点名到条目 id）
        List<CaseGroups.ItemCase> cases = new ArrayList<>(rows.size());
        for (DecisionDatasetItemEntity row : rows) {
            cases.add(new CaseGroups.ItemCase(row.getId(), row.getCaseGroup(), json.parse(row.getStateJson())));
        }
        Map<Long, String> violations = CaseGroups.violations(cases);
        if (!violations.isEmpty()) {
            List<String> detail = violations.entrySet().stream()
                    .limit(5).map(en -> "#" + en.getKey() + " " + en.getValue()).toList();
            blockers.add("对照组与内容不符（" + violations.size() + " 条）：" + String.join("；", detail)
                    + (violations.size() > detail.size() ? " 等" : ""));
        }

        // ③ 题面版本唯一且不为空（混版本 = 报告里的指标没有共同分母）
        Map<String, Long> versions = questionSetCounts(rows);
        if (versions.size() > 1) {
            blockers.add("题面版本不一致：" + versions.keySet() + "——同一份报告里的样本必须测同一套题面");
        } else if (singleQuestionSetVersion(rows).isEmpty()) {
            // 题面版本没戳上：报告里的"测的是哪套题面"就无从谈起，而历史指标会因此没法被判定可比
            blockers.add("样本缺题面版本（未记录标注时的题面）——" + rows.size()
                    + " 条样本都要有版本戳，否则这份报告测的是哪套题面无从考据");
        }
        return blockers;
    }

    /** 冻结时给出的提醒（不阻断）：小样本与标注不全都会让指标的解读变味，得写在报告旁边 */
    private List<String> freezeWarnings(DecisionDatasetEntity dataset, List<DecisionDatasetItemEntity> rows) {
        List<String> warnings = new ArrayList<>();
        if (DecisionDatasetEntity.KIND_BENCHMARK.equals(dataset.getKind())
                && rows.size() < SUGGESTED_BENCHMARK_ITEMS) {
            warnings.add("样本量 " + rows.size() + " 偏少（FR-02 建议 " + SUGGESTED_BENCHMARK_ITEMS
                    + "-100 起步）：小样本的准确率摆动大，看指标时必须连随机/多数类基线一起看");
        }
        List<DatasetDetail.QuestionCoverage> coverage = coverage(rows);
        List<String> partial = coverage.stream().filter(c -> c.annotated() < rows.size())
                .map(c -> c.questionId() + " " + c.annotated() + "/" + rows.size()).toList();
        if (!partial.isEmpty()) {
            warnings.add("有题没标全（" + String.join("、", partial) + "）：该题的指标只按已标的那些条算，分母比别的题小");
        }
        return warnings;
    }

    /**
     * 单条样本的校验（加与改共用）。
     *
     * <p>题面必须与当前标准题面<b>逐字相同</b>：CAP-56 §8 明确一期只服务 {@code kb-proposal-triage}
     * 这一套题面，多个题面并行会让"题面版本进指标主键"这件事失去意义（每套题面各要一套解读口径）。
     * 拒的时候要说清哪里不同——只报"题面不合法"的话，从历史记录回流时会根本不知道差在哪。</p>
     */
    private void validate(DecisionDatasetItemEntity e, DatasetItemRequest req) {
        if (req == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "请求体为空");
        }
        Map<String, Object> state = req.state();
        if (state == null || state.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "state 不能为空：没有输入就没有可评测的样本");
        }
        Map<String, Object> gold = req.gold();
        if (gold == null || gold.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "gold 不能为空：没有标准答案的样本评不出对错");
        }
        String group = CaseGroups.normalise(req.caseGroup());
        if (group == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "caseGroup 只能是 " + CaseGroups.ALL + "（认不出的值不会被当成普通样本，否则对照组红线会被静默绕过）");
        }

        Map<String, Map<String, Object>> questions = questionsOf(req.questions());
        List<String> diff = QuestionSets.diff(questions);
        if (!diff.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "题面与当前标准题面不一致（" + String.join("；", diff) + "）——"
                            + "一期只评测 " + TriageQuestions.VERSION + " 这一套题面（见 CAP-56 §8）");
        }

        // 对照组必须名副其实（内容层面），不等冻结才说
        Optional<String> issue = CaseGroups.holds(group, state);
        if (issue.isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT, issue.get());
        }

        // gold 至少有一题能落上题面，否则这条样本等于没标
        Map<String, Object> distributions = DatasetViews.distributions(questions, gold);
        if (distributions.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "gold 落不上题面：题面里有 " + questions.keySet() + "，gold 给的是 " + gold.keySet()
                            + "——值要落在选项内（choice 给选项 key、score 给等级下标、noul 给是/否）");
        }

        e.setStateJson(json.write(state));
        e.setQuestionsJson(json.write(questions));
        e.setGoldJson(json.write(gold));
        // 题面已校验等于标准题面，版本直接盖当前值；将来题面升版时，旧样本保留旧版本戳，
        // 于是"混版本"会在冻结时被抓住，而不是被悄悄对齐到新题面
        e.setQuestionSetVersion(TriageQuestions.VERSION);
        e.setCaseGroup(group);
        e.setNote(trimTo(req.note(), MAX_NOTE_CHARS));
    }

    private Map<String, Map<String, Object>> questionsOf(Map<String, Object> provided) {
        if (provided == null || provided.isEmpty()) {
            return TriageQuestions.standard();
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : provided.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "题面 " + entry.getKey() + " 必须是对象（{type,instructions,criteria}）");
            }
            Map<String, Object> q = new LinkedHashMap<>();
            for (Map.Entry<?, ?> kv : raw.entrySet()) {
                q.put(String.valueOf(kv.getKey()), kv.getValue());
            }
            out.put(entry.getKey(), q);
        }
        return out;
    }

    // ---------------- 内部：统计 ----------------

    /** 各组条数：全部组都出现在结果里（缺的记 0），界面才能一眼看出缺哪个对照 */
    private Map<String, Long> caseGroupCounts(List<DecisionDatasetItemEntity> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String group : CaseGroups.ALL) {
            counts.put(group, 0L);
        }
        for (DecisionDatasetItemEntity row : rows) {
            String group = row.getCaseGroup() == null ? CaseGroups.NORMAL : row.getCaseGroup();
            counts.merge(group, 1L, Long::sum);
        }
        return counts;
    }

    /** 题面版本分布（含 null 键：题面没戳版本的样本也要能被看见，而不是消失） */
    private Map<String, Long> questionSetCounts(List<DecisionDatasetItemEntity> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DecisionDatasetItemEntity row : rows) {
            counts.merge(row.getQuestionSetVersion() == null ? "（未记录）" : row.getQuestionSetVersion(),
                    1L, Long::sum);
        }
        return counts;
    }

    /** 唯一题面版本（冻结时用；调用前已确认过只有一种） */
    private Optional<String> singleQuestionSetVersion(List<DecisionDatasetItemEntity> rows) {
        return rows.stream().map(DecisionDatasetItemEntity::getQuestionSetVersion)
                .filter(v -> v != null && !v.isBlank()).findFirst();
    }

    /** 每题已标注条数：题面取标准三题（样本已被校验必须等于标准题面），分母即集内条数 */
    private List<DatasetDetail.QuestionCoverage> coverage(List<DecisionDatasetItemEntity> rows) {
        Map<String, Map<String, Object>> questions = TriageQuestions.standard();
        // 每条只解析一次（三题共用）：几百条 × 三题若逐题重解析，详情页会白读三倍的 JSON
        List<Set<String>> perRow = new ArrayList<>(rows.size());
        for (DecisionDatasetItemEntity row : rows) {
            perRow.add(new TreeSet<>(DatasetViews.annotated(
                    json.parseQuestions(row.getQuestionsJson()), json.parse(row.getGoldJson()))));
        }
        List<DatasetDetail.QuestionCoverage> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : questions.entrySet()) {
            long annotated = perRow.stream().filter(set -> set.contains(entry.getKey())).count();
            out.add(new DatasetDetail.QuestionCoverage(entry.getKey(),
                    String.valueOf(entry.getValue().get("type")), annotated));
        }
        return out;
    }

    /** 冻结旁证：冻的是什么内容，一眼可读（事后想核对"当时那版冻的是这些吗"不用翻日志） */
    private Map<String, Object> manifest(DecisionDatasetEntity dataset, List<DecisionDatasetItemEntity> rows) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("datasetVersion", dataset.getVersion());
        manifest.put("itemCount", rows.size());
        manifest.put("questionSetVersion", dataset.getQuestionSetVersion());
        manifest.put("caseGroupCounts", caseGroupCounts(rows));
        manifest.put("frozenAt", String.valueOf(dataset.getFrozenAt()));
        return manifest;
    }

    /** 详情里回显的旁证（草稿为空 map：还没冻，别给一个看着像旁证的空壳） */
    private Map<String, Object> manifestOf(DecisionDatasetEntity dataset) {
        if (!dataset.isFrozen() || dataset.getFreezeManifest() == null) {
            return Map.of();
        }
        return json.parse(dataset.getFreezeManifest());
    }

    private void refreshCount(DecisionDatasetEntity dataset) {
        dataset.setItemCount((int) itemRepo.countByDatasetId(dataset.getId()));
        dataset.setUpdatedAt(Instant.now());
        repo.save(dataset);
    }

    // ---------------- 内部：取数 ----------------

    public DecisionDatasetEntity requireDataset(Long id) {
        return repo.findById(id).orElseThrow(() ->
                new DevMindException(ErrorCode.NOT_FOUND, "评测集不存在: " + id));
    }

    /** 草稿专用入口：冻结集一律拒绝写，并把"该怎么办"写进提示（用户不该需要自己想） */
    private DecisionDatasetEntity requireDraft(Long id) {
        DecisionDatasetEntity e = requireDataset(id);
        if (e.isFrozen()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "评测集「" + e.getName() + "」v" + e.getVersion() + " 已冻结，不可修改——"
                            + "要改请用「修订为新版本」，原版本留给已跑过的报告");
        }
        return e;
    }

    private DecisionDatasetItemEntity requireItem(Long datasetId, Long itemId) {
        DecisionDatasetItemEntity e = itemRepo.findById(itemId).orElseThrow(() ->
                new DevMindException(ErrorCode.NOT_FOUND, "样本不存在: " + itemId));
        if (!e.getDatasetId().equals(datasetId)) {
            throw new DevMindException(ErrorCode.NOT_FOUND,
                    "样本 " + itemId + " 不属于评测集 " + datasetId);
        }
        return e;
    }

    private static DecisionDatasetItemEntity copyOf(DecisionDatasetItemEntity src, Long datasetId) {
        DecisionDatasetItemEntity copy = new DecisionDatasetItemEntity();
        copy.setDatasetId(datasetId);
        copy.setStateJson(src.getStateJson());
        copy.setQuestionsJson(src.getQuestionsJson());
        copy.setGoldJson(src.getGoldJson());
        copy.setSource(src.getSource());
        copy.setOriginRecordId(src.getOriginRecordId());
        copy.setCaseGroup(src.getCaseGroup());
        copy.setQuestionSetVersion(src.getQuestionSetVersion());
        copy.setNote(src.getNote());
        copy.setCreatedAt(Instant.now());
        return copy;
    }

    // ---------------- 内部：小工具 ----------------

    private static String requireName(DatasetRequest req) {
        String name = req == null ? null : trimTo(req.name(), MAX_NAME_CHARS);
        if (name == null || name.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "评测集名不能为空");
        }
        return name;
    }

    private static String requireKind(DatasetRequest req) {
        String kind = req == null || req.kind() == null ? null : req.kind().trim().toUpperCase();
        if (!DecisionDatasetEntity.KIND_BENCHMARK.equals(kind)
                && !DecisionDatasetEntity.KIND_REPLAY.equals(kind)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "kind 只能是 " + DecisionDatasetEntity.KIND_BENCHMARK + "（人工标注）或 "
                            + DecisionDatasetEntity.KIND_REPLAY + "（决策记录回流）");
        }
        return kind;
    }

    private static int pageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "size 取值范围 1-" + MAX_PAGE_SIZE);
        }
        return size;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
