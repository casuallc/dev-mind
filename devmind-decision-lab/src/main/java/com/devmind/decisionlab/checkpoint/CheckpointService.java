package com.devmind.decisionlab.checkpoint;

import com.devmind.auth.IdentityService;
import com.devmind.common.decision.DecisionGate;
import com.devmind.common.dto.PageView;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.checkpoint.dto.CheckpointDetail;
import com.devmind.decisionlab.checkpoint.dto.CheckpointGateView;
import com.devmind.decisionlab.checkpoint.dto.CheckpointRequest;
import com.devmind.decisionlab.checkpoint.dto.CheckpointVerifyRequest;
import com.devmind.decisionlab.checkpoint.dto.CheckpointView;
import com.devmind.decisionlab.checkpoint.dto.CheckpointViews;
import com.devmind.decisionlab.checkpoint.dto.ServeCheckResult;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import com.devmind.decisionlab.checkpoint.repo.DecisionCheckpointRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAP-56 FR-06/FR-07 产物登记与准入：登记 → serve 自检 → 人工验证（→ 撤销 / 删除）。
 *
 * <p><b>闸门只认 {@code verified}</b>，而 {@code verified} 只能由人按下——这个模块里最要紧的一条规则，
 * 因为它决定了"知识库分诊会不会用一个没人看过的模型"。2026-09-22 那次退化不是"模型挂了"，
 * 是"模型好好的，答得整齐又自信，而没人看过它准不准"。所以：
 * <ul>
 *   <li>验证要写判断依据（{@link #verify}），撤销也要写原因（{@link #unverify}）；</li>
 *   <li>验证要能指认"放行的到底是哪一份"（微调产物必须有 sha256）；</li>
 *   <li>同槽位互斥——边车一个槽位只加载一份权重，"现在放行的是谁"必须唯一；</li>
 *   <li>登记信息写错了就删掉重建：未验证的行随便删，已验证的行删不掉（删除 = 闸门当场关闭）。</li>
 * </ul>
 * 不复用"编辑"接口是<b>刻意</b>的：一个能改来源路径/指纹的 PATCH，等于给"验证之后偷偷换掉权重"
 * 留了后门，而验证依据（verified_note）说的还是老那份。要改就删了重来，审计链上留下一道清楚的痕。</p>
 *
 * <p>事务口径：写操作都是短事务，同步的；异步触发（评测/微调）在 FR-03/FR-05，按红线禁
 * {@code @Transactional}。serve 自检虽然要发网络请求，但它由人点、单次 10 秒超时，放在事务里没有
 * "异步线程看不到未提交行"的风险——它不触发任何异步流程。</p>
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_NAME_CHARS = 128;
    private static final int MAX_SLOT_CHARS = 64;
    private static final int MAX_PATH_CHARS = 512;
    private static final int MAX_NOTE_CHARS = 512;

    private final DecisionCheckpointRepository repo;
    private final CheckpointJson json;
    private final IdentityService identityService;
    private final ServeCheckService serveCheck;
    private final ObjectProvider<DecisionGate> gates;

    public CheckpointService(DecisionCheckpointRepository repo, CheckpointJson json,
                             IdentityService identityService, ServeCheckService serveCheck,
                             ObjectProvider<DecisionGate> gates) {
        this.repo = repo;
        this.json = json;
        this.identityService = identityService;
        this.serveCheck = serveCheck;
        this.gates = gates;
    }

    // ---------------- 登记 ----------------

    @Transactional
    public CheckpointView create(CheckpointRequest req) {
        String name = requireName(req);
        if (repo.findByName(name).isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "已有同名产物「" + name + "」——重训一份请换个名字（如加日期/轮次后缀），"
                            + "覆盖名字会让历史报告指不回它当时测的那份");
        }
        DecisionCheckpointEntity e = new DecisionCheckpointEntity();
        e.setName(name);
        e.setKind(requireKind(req));
        e.setServeSlot(requireSlot(req));
        e.setSourcePath(requireSource(req));
        e.setNodeId(trimTo(req.nodeId(), MAX_SLOT_CHARS));
        e.setFingerprintPath(trimTo(req.fingerprintPath(), MAX_PATH_CHARS));
        e.setFingerprintBytes(req.fingerprintBytes());
        e.setFingerprintSha256(normalizeSha(req == null ? null : req.fingerprintSha256()));
        e.setMetricsJson(trimJson(req == null ? null : req.metricsJson()));
        e.setVerified(Boolean.FALSE);
        e.setCreatedBy(identityService.currentActor());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(e.getCreatedAt());
        e.setNote(trimTo(req == null ? null : req.note(), MAX_NOTE_CHARS));
        if (DecisionCheckpointEntity.KIND_FINETUNED.equals(e.getKind())
                && (e.getFingerprintSha256() == null || e.getFingerprintSha256().isBlank())) {
            // 自己训出来的产物一定算得出指纹（就在节点上那个目录里），没有指纹就没法回答
            // "放行的到底是哪一份"：路径会被下一轮训练就地覆盖，只有 sha256 认得出来
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "微调产物必须带指纹 sha256：它证明「放行的是这一份」（输出目录会被下一轮覆盖，路径不算凭据）");
        }
        DecisionCheckpointEntity saved = repo.save(e);
        log.info("产物登记: id={} name={} slot={} kind={} by={}", saved.getId(), saved.getName(),
                saved.getServeSlot(), saved.getKind(), saved.getCreatedBy());
        return CheckpointViews.of(saved);
    }

    // ---------------- 查 ----------------

    public PageView<CheckpointView> list(String serveSlot, int page, int size) {
        int p = Math.max(page, 0);
        int s = pageSize(size);
        String slot = blankToNull(serveSlot);
        Page<DecisionCheckpointEntity> result = slot == null
                ? repo.findAllByOrderByIdDesc(PageRequest.of(p, s))
                : repo.pageByServeSlotOrderByIdDesc(slot, PageRequest.of(p, s));
        return new PageView<>(result.getContent().stream().map(CheckpointViews::of).toList(),
                result.getTotalElements(), p, s);
    }

    public CheckpointDetail detail(Long id) {
        DecisionCheckpointEntity e = require(id);
        return new CheckpointDetail(CheckpointViews.of(e), json.parse(e.getMetricsJson()),
                json.parse(e.getCalibrationJson()), json.parse(e.getServeCheckJson()));
    }

    /**
     * 闸门当前状态：直接问 SPI（与分诊按钮置灰同一个上游），顺带列出正在放行的产物。
     *
     * <p>页面上的横幅与按钮状态因此不可能说法不一——它们本来就是同一个问题的同一个答案。</p>
     */
    public CheckpointGateView gate() {
        String reason = gates.orderedStream()
                .map(DecisionGate::unavailableReason)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(null);
        List<CheckpointView> serving = repo.findByVerifiedTrueOrderByIdAsc().stream()
                .map(CheckpointViews::of).toList();
        return new CheckpointGateView(reason == null, reason, serving, Instant.now());
    }

    // ---------------- 放行 / 撤销 / 删除 ----------------

    /**
     * 人工验证通过 = 打开闸门。
     *
     * <p>同槽位互斥在这里保证：验证这份时把同槽位其它已放行的行放下——不是"顺手清理"，
     * 而是"边车一个槽位只加载一份权重"的直译。被顶掉的行保留 {@code verified_by/at}
     * （谁在什么时候凭什么验过它，这段历史不因失宠而作废），只把开关关掉。</p>
     */
    @Transactional
    public CheckpointView verify(Long id, CheckpointVerifyRequest req) {
        DecisionCheckpointEntity e = require(id);
        if (e.isVerified()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "「" + e.getName() + "」已经在放行中——要改判断依据请先「撤销放行」再重新验证");
        }
        String note = req == null || req.note() == null ? "" : req.note().trim();
        if (note.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "验证通过必须写下判断依据（跑了哪个评测集、哪个指标、与基线比如何）——"
                            + "一个不需要理由就能按下的放行开关，出问题时只剩「某天有人点了一下」");
        }
        requireIdentifiable(e);
        List<DecisionCheckpointEntity> superseded =
                repo.findByServeSlotAndVerifiedTrueOrderByIdAsc(e.getServeSlot());
        for (DecisionCheckpointEntity other : superseded) {
            other.setVerified(Boolean.FALSE);
            other.setUpdatedAt(Instant.now());
            repo.save(other);
            log.info("同槽位互斥：产物 {}「{}」被 {}「{}」顶掉放行", other.getId(), other.getName(),
                    e.getId(), e.getName());
        }
        e.setVerified(Boolean.TRUE);
        e.setVerifiedBy(identityService.currentActor());
        e.setVerifiedAt(Instant.now());
        e.setVerifiedNote(trimTo(note, MAX_NOTE_CHARS));
        e.setUpdatedAt(e.getVerifiedAt());
        DecisionCheckpointEntity saved = repo.save(e);
        log.info("产物放行: id={} slot={} by={} note={}", saved.getId(), saved.getServeSlot(),
                saved.getVerifiedBy(), saved.getVerifiedNote());
        return CheckpointViews.of(saved);
    }

    /**
     * 撤销放行：闸门<b>立即</b>关闭（{@code HttpDecisionEngine} 每次询问都重新查库，不缓存）。
     *
     * <p>撤销原因追加进 {@code verified_note}（不是覆盖）：那一栏是这行产物"放行史"的载体，
     * "凭什么放行"与"为什么收回"都是它的一部分。</p>
     */
    @Transactional
    public CheckpointView unverify(Long id, CheckpointVerifyRequest req) {
        DecisionCheckpointEntity e = require(id);
        if (!e.isVerified()) {
            throw new DevMindException(ErrorCode.CONFLICT, "「" + e.getName() + "」当前并未在放行");
        }
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "撤销放行必须写明原因（事后再回看时，原因才是全部价值）");
        }
        e.setVerified(Boolean.FALSE);
        e.setUpdatedAt(Instant.now());
        String stamp = "（" + e.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                + " 撤销放行：" + reason + "）";
        e.setVerifiedNote(trimTo(joinNote(e.getVerifiedNote(), stamp), MAX_NOTE_CHARS));
        DecisionCheckpointEntity saved = repo.save(e);
        log.info("撤销放行: id={} slot={} by={} reason={}", saved.getId(), saved.getServeSlot(),
                identityService.currentActor(), reason);
        return CheckpointViews.of(saved);
    }

    /** 删除：已验证的不许删——删掉它 = 闸门当场关闭（决策能力无模型可用），这不该由一个删按钮顺手做掉 */
    @Transactional
    public void delete(Long id) {
        DecisionCheckpointEntity e = require(id);
        if (e.isVerified()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "「" + e.getName() + "」正在放行中（已通过验证），不可删除——"
                            + "先撤销验证、或先验证另一份产物，再回来删");
        }
        repo.delete(e);
        log.info("产物登记删除: id={} name={}", e.getId(), e.getName());
    }

    // ---------------- 结果回写（FR-03/FR-05 的落点） ----------------

    /**
     * 把一次评测（或微调结束时的回评）的结论写回产物行：指标 + 温度校准参数。
     *
     * <p><b>为什么要回写</b>：{@code decision_checkpoints.metrics_json} 是"这份产物值不值得放行"的
     * 第一手依据（人按下验证按钮前看的就是它）。让报告只躺在评测行里，等于每次放行都要人去翻
     * "最近一次评测是哪一条"——而 FR-07 的闸门恰恰要人<b>看着指标</b>做决定。</p>
     *
     * <p><b>只在真有报告时写</b>：一次跑完却没产出报告的运行（脚本崩了、漏打 marker）不覆盖已有指标。
     * 那份旧报告是"上次测出来是这样"，把它抹成空不会让人知道更多，只会让"这份产物测过没有"
     * 变成"没测过"。所以这里写的是<b>最近一次产出报告的评测</b>的结论；
     * 完整的评测时间线在评测列表里，不受影响。</p>
     *
     * <p><b>校准单独判断</b>：报告里没带 {@code calibration} 时保留已有参数——那是更可信的一次
     * held-out 拟合留下的（微调在验证切分上拟合），不该被一次没做校准的评测抹掉。</p>
     *
     * @param metricsJson     报告 JSON（空 = 本次没有结论，不动指标列）
     * @param calibrationJson 温度校准 JSON（空 = 本次没校准，不动校准列）
     * @return 真的写回了任何一列
     */
    @Transactional
    public boolean attachEvalResult(Long id, String metricsJson, String calibrationJson) {
        String metrics = blankToNull(metricsJson);
        String calibration = blankToNull(calibrationJson);
        if (id == null || (metrics == null && calibration == null)) {
            return false;
        }
        DecisionCheckpointEntity e = repo.findById(id).orElse(null);
        if (e == null) {
            // 产物行被删了（未验证的行可以随便删）：评测本身是成功的，不能因此失败
            log.warn("产物 {} 已不存在，本次指标未写回（评测/微调本身不受影响）", id);
            return false;
        }
        if (metrics != null) {
            e.setMetricsJson(metrics);
        }
        if (calibration != null) {
            e.setCalibrationJson(calibration);
        }
        e.setUpdatedAt(Instant.now());
        repo.save(e);
        log.info("产物 {}「{}」回写: 指标={} 校准={}", e.getId(), e.getName(),
                metrics == null ? "未动" : "已更新", calibration == null ? "未动" : "已更新");
        return true;
    }

    // ---------------- serve 自检 ----------------

    /** 打边车问一次"你现在服务的是哪份"，落库（结论状态冗余一列，列表页免解析） */
    @Transactional
    public ServeCheckResult serveCheck(Long id) {
        DecisionCheckpointEntity e = require(id);
        ServeCheckResult result = serveCheck.run(e);
        e.setServeCheckJson(json.write(result));
        e.setServeCheckStatus(result.status());
        e.setServeCheckedAt(result.checkedAt());
        e.setUpdatedAt(result.checkedAt());
        repo.save(e);
        log.info("serve 自检: id={} slot={} → {}", e.getId(), e.getServeSlot(), result.summary());
        return result;
    }

    // ---------------- 内部 ----------------

    /**
     * 取产物实体（不存在 → 404）。
     *
     * <p>public：同模块的评测/微调编排与打包供给都要按 id 取它，且应当复用同一句错误文案——
     * 各写一份的话，"产物不存在"会在不同入口有不同说法。</p>
     */
    public DecisionCheckpointEntity require(Long id) {
        return repo.findById(id).orElseThrow(() ->
                new DevMindException(ErrorCode.NOT_FOUND, "决策模型产物不存在: " + id));
    }

    /**
     * "能指认得出这是哪一份"才准放行。
     *
     * <p>微调产物要 sha256（路径会被下一轮训练就地覆盖）；官方基础模型至少要有来源
     * （HF repo id 或本地目录）——两者都没有的话，这份"放行"就只是在放行一个名字。</p>
     */
    private static void requireIdentifiable(DecisionCheckpointEntity e) {
        boolean finetuned = DecisionCheckpointEntity.KIND_FINETUNED.equals(e.getKind());
        if (finetuned && isBlank(e.getFingerprintSha256())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "微调产物「" + e.getName() + "」没有指纹 sha256，不能放行——"
                            + "没有它就无法回答「现在跑的到底是哪一份」（输出目录会被下一轮训练覆盖）");
        }
        if (!finetuned && isBlank(e.getSourcePath()) && isBlank(e.getFingerprintPath())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "「" + e.getName() + "」既没有来源路径也没有指纹路径，不能放行——"
                            + "至少要说得出这份权重从哪来");
        }
    }

    private static String requireName(CheckpointRequest req) {
        String name = req == null ? null : trimTo(req.name(), MAX_NAME_CHARS);
        if (name == null || name.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "产物名不能为空");
        }
        return name;
    }

    private static String requireKind(CheckpointRequest req) {
        String kind = req == null || req.kind() == null ? null : req.kind().trim().toUpperCase(Locale.ROOT);
        if (!DecisionCheckpointEntity.KIND_BASE.equals(kind)
                && !DecisionCheckpointEntity.KIND_FINETUNED.equals(kind)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "kind 只能是 " + DecisionCheckpointEntity.KIND_BASE + "（HF 官方 checkpoint）或 "
                            + DecisionCheckpointEntity.KIND_FINETUNED + "（RLCD 微调产物）");
        }
        return kind;
    }

    private static String requireSlot(CheckpointRequest req) {
        String slot = req == null ? null : trimTo(req.serveSlot(), MAX_SLOT_CHARS);
        if (slot == null || slot.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "服务槽位不能为空（边车 LAYA_SLOT_MODELS 的键，如 multilingual）——"
                            + "槽位是这份产物与「边车在哪服务它」之间唯一的联系");
        }
        return slot;
    }

    private static String requireSource(CheckpointRequest req) {
        String source = req == null ? null : trimTo(req.sourcePath(), MAX_PATH_CHARS);
        if (source == null || source.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "来源路径不能为空（HF repo id 或节点上的绝对路径）");
        }
        return source;
    }

    /** sha256 统一小写并校验长度：算的时候各家大小写不一，比对时必须同口径 */
    private static String normalizeSha(String sha) {
        String s = blankToNull(sha);
        if (s == null) {
            return null;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.length() != 64 || !lower.matches("[0-9a-f]{64}")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "sha256 应为 64 位十六进制，实际: " + s);
        }
        return lower;
    }

    private static String trimJson(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String joinNote(String note, String stamp) {
        return note == null || note.isBlank() ? stamp : note.trim() + " " + stamp;
    }

    private static int pageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "size 取值范围 1-" + MAX_PAGE_SIZE);
        }
        return size;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
