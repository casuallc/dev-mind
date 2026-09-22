package com.devmind.model;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.model.DecisionAnswer;
import com.devmind.common.model.LayaDecisionClient;
import com.devmind.common.model.ModelCallException;
import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointUsageProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.common.model.OpenAiCompatChat;
import com.devmind.common.model.OpenAiCompatEmbeddings;
import com.devmind.model.config.EmbeddingSeedProperties;
import com.devmind.model.dto.EndpointTestResult;
import com.devmind.model.dto.ModelEndpointApiView;
import com.devmind.model.dto.ModelEndpointRequest;
import com.devmind.model.repo.ModelEndpointRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CAP-48 端点管理主服务，同时是 {@link ModelEndpointProvider} 的 SPI 实现。
 *
 * <p>几条不可动摇的规则：</p>
 * <ul>
 *   <li><b>维度不接收人工输入</b>——{@link ModelEndpointRequest} 里没有这个字段，
 *       唯一的写入路径是连接测试的实测探测结果；</li>
 *   <li><b>连接测试的 HTTP 调用在事务之外</b>——网络 IO 不占数据库连接
 *       （探测写回走 {@link TransactionTemplate} 的短事务）；</li>
 *   <li><b>凭据只在内存解密</b>——HTTP 视图只有 hasApiKey，日志与异常消息过脱敏。</li>
 * </ul>
 */
@Service
public class ModelEndpointService implements ModelEndpointProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelEndpointService.class);

    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_BATCH_SIZE = 32;
    /** 探针文本：只为拿回一个向量看维度，内容无意义 */
    static final String PROBE_TEXT = "__devmind_probe__";
    /** FR-11 对话探针文本：要求模型"回话"，好把回复摘要一并展示出来（证明它真的是个对话模型） */
    static final String CHAT_PROBE_TEXT = "请回复两个字：可用";
    /** 对话探针回复摘要长度（进 message，会落 last_test_message 并显示在 UI） */
    static final int CHAT_REPLY_SNIPPET_LEN = 80;

    private final ModelEndpointRepository repo;
    private final ModelCipher cipher;
    private final EmbeddingSeedProperties seed;
    private final TransactionTemplate txTemplate;
    private final ObjectProvider<ModelEndpointUsageProvider> usageProviders;

    public ModelEndpointService(ModelEndpointRepository repo,
                                ModelCipher cipher,
                                EmbeddingSeedProperties seed,
                                TransactionTemplate txTemplate,
                                ObjectProvider<ModelEndpointUsageProvider> usageProviders) {
        this.repo = repo;
        this.cipher = cipher;
        this.seed = seed;
        this.txTemplate = txTemplate;
        this.usageProviders = usageProviders;
    }

    // ---------------- FR-01 CRUD ----------------

    public List<ModelEndpointApiView> list() {
        return repo.findAllByOrderByIdAsc().stream().map(ModelEndpointService::view).toList();
    }

    public ModelEndpointApiView get(long id) {
        return view(require(id));
    }

    @Transactional
    public ModelEndpointApiView create(ModelEndpointRequest req) {
        ModelEndpointEntity e = new ModelEndpointEntity();
        e.setKind(kind(req.kind()));
        e.setProvider(provider(e.getKind(), req.provider()));
        e.setName(requireName(req.name()));
        applyTransport(e, req, null);
        e.setApiKeyEnc(cipher.encrypt(blankToNull(req.apiKey())));
        e.setStatus(statusOrDefault(req.status()));
        e.setDefault(false);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        ModelEndpointEntity saved = repo.save(e);
        log.info("模型端点已创建: id={} provider={} model={} 默认={}",
                saved.getId(), saved.getProvider(), saved.getModel(), saved.isDefault());
        return view(saved);
    }

    @Transactional
    public ModelEndpointApiView update(long id, ModelEndpointRequest req) {
        ModelEndpointEntity e = require(id);
        if (req.kind() != null && !req.kind().isBlank()) {
            String k = kind(req.kind());
            if (!k.equals(e.getKind())) {
                throw new DevMindException(ErrorCode.BAD_REQUEST,
                        "端点类型不可变更（当前 " + e.getKind() + "），请新建端点");
            }
        }
        if (req.provider() != null && !req.provider().isBlank()) {
            // kind 不可变更 → 用端点自己的 kind 校验 provider
            e.setProvider(provider(e.getKind(), req.provider()));
        }
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
        }
        applyTransport(e, req, e);
        // apiKey 留空 = 保持不变（避免把前端回显的空密码框当成"清空密钥"）
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            e.setApiKeyEnc(cipher.encrypt(req.apiKey().trim()));
        }
        if (req.status() != null && !req.status().isBlank()) {
            applyStatus(e, status(req.status()));
        }
        e.setUpdatedAt(Instant.now());
        return view(repo.save(e));
    }

    /** 删除端点；被知识库等资源引用时 409 并列出引用方（可停用代替删除）。 */
    @Transactional
    public void delete(long id) {
        ModelEndpointEntity e = require(id);
        List<String> usages = usagesOf(id);
        if (!usages.isEmpty()) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "端点被以下资源引用，无法删除：" + String.join("、", usages) + "（可改为停用）");
        }
        repo.delete(e);
        if (e.isDefault()) {
            log.warn("平台默认端点 {} [{}] 已删除{}", id, e.getKind(), missingDefaultHint(e.getKind()));
        }
    }

    @Transactional
    public ModelEndpointApiView changeStatus(long id, String status) {
        ModelEndpointEntity e = require(id);
        applyStatus(e, status(status));
        e.setUpdatedAt(Instant.now());
        return view(repo.save(e));
    }

    /** 设为平台默认：同类型旧默认在单事务内取消（表上无部分唯一索引，靠本方法保证唯一）。 */
    @Transactional
    public ModelEndpointApiView setDefault(long id) {
        ModelEndpointEntity e = require(id);
        if (!e.active()) {
            throw new DevMindException(ErrorCode.CONFLICT, "停用的端点不能设为平台默认，请先启用");
        }
        Instant now = Instant.now();
        repo.clearDefaultExcept(e.getKind(), id, now);
        e.setDefault(true);
        e.setUpdatedAt(now);
        ModelEndpointEntity saved = repo.save(e);
        log.info("平台默认端点已切换: {} [{}]", saved.getId(), saved.getName());
        return view(saved);
    }

    // ---------------- FR-03 连接测试 ----------------

    /**
     * 已存端点连接测试：按 kind 实调一次（EMBEDDING → {@code /embeddings} 并回写<b>实测维度</b>；
     * CHAT → {@code /chat/completions}）。HTTP 调用刻意留在事务外——这是网络 IO，不该占着数据库连接。
     */
    public EndpointTestResult test(long id) {
        ModelEndpointEntity e = require(id);
        Integer before = e.getDimensions();
        String key = null;
        if (e.getApiKeyEnc() != null && !e.getApiKeyEnc().isBlank()) {
            try {
                key = cipher.decrypt(e.getApiKeyEnc());
            } catch (Exception ex) {
                EndpointTestResult r = new EndpointTestResult(false, 0, e.getModel(), null,
                        "凭据解密失败（密钥变更或密文损坏），请重新填写密钥", null);
                persistTestResult(id, r, null, false);
                return r;
            }
        }
        EndpointTestResult r = probe(e.getKind(), e.getProvider(), e.getBaseUrl(), key, e.getModel(),
                e.getTimeoutSeconds(), before);
        if (r.ok() && r.dimensions() != null && before != null && !before.equals(r.dimensions())) {
            EndpointTestResult.DimensionChange changed =
                    new EndpointTestResult.DimensionChange(before, r.dimensions());
            r = new EndpointTestResult(true, r.latencyMs(), r.model(), r.dimensions(), r.message(), changed);
            log.warn("端点 {} 维度变化 {} → {}：该端点上已建的索引需重建", id, before, r.dimensions());
        }
        persistTestResult(id, r, r.ok() ? r.dimensions() : null, true);
        return r;
    }

    /** 草稿预检：凭据不落库（新建/编辑表单内先测再存）。kind 也要校验——否则预留类型（RERANK）的草稿会静默打到 /embeddings。 */
    public EndpointTestResult testDraft(ModelEndpointRequest req) {
        String k = kind(req.kind());
        String p = provider(k, req.provider());
        return probe(k, p, blankToNull(req.baseUrl()), blankToNull(req.apiKey()), blankToNull(req.model()),
                req.timeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : req.timeoutSeconds(), null);
    }

    /**
     * 探针调用，按 kind 分派：{@code mock} 自报（无远端可探）；{@code openai-compatible} 实调一次；
     * {@code laya} 实调边车。CHAT 与 DECISION 都没有维度概念——{@code dimensions} 恒 null，
     * 也就不会产生维度变化告警。
     *
     * @param prevDimensions 已存端点的原维度；null = 新建/草稿（mock 分支据此决定自报值）
     */
    private EndpointTestResult probe(String kind, String provider, String baseUrl, String apiKey, String model,
                                     int timeoutSeconds, Integer prevDimensions) {
        boolean chat = ModelEndpointEntity.KIND_CHAT.equals(kind);
        boolean decision = ModelEndpointEntity.KIND_DECISION.equals(kind);
        if (ModelEndpointEntity.PROVIDER_MOCK.equalsIgnoreCase(provider)) {
            if (chat) {
                // 不复用 MODEL_MOCK（mock-embedding）——那是索引血缘的合同值，只对 EMBEDDING 有意义
                return new EndpointTestResult(true, 0L, model, null,
                        "mock provider 假回复（无远端可探测）", null);
            }
            if (decision) {
                // 决策端点的 model 是 checkpoint 别名，可空 → 不冒用 mock-embedding
                return new EndpointTestResult(true, 0L, model, null,
                        "mock provider 假决策（无远端可探测）", null);
            }
            int dims = prevDimensions != null ? prevDimensions : seed.getDimensions();
            return new EndpointTestResult(true, 0L, ModelEndpointView.MODEL_MOCK, dims,
                    "mock provider 自报维度 " + dims + "（无远端可探测）", null);
        }
        // 决策端点的 model 是 checkpoint 别名、可空（空 = 边车自己路由），故不参与必填判定
        if (baseUrl == null || (!decision && model == null)) {
            return new EndpointTestResult(false, 0L, model, null,
                    decision ? "baseUrl 必填后才能测试 laya 决策端点（模型名可空 = 由边车选 checkpoint）"
                            : "baseUrl 与 model 必填后才能测试 openai-compatible 端点", null);
        }
        long t0 = System.nanoTime();
        try {
            if (decision) {
                return probeDecision(baseUrl, apiKey, model, timeoutSeconds, t0);
            }
            if (chat) {
                String reply = OpenAiCompatChat.chat(
                        new OpenAiCompatChat.Options(baseUrl, apiKey, model, timeoutSeconds), CHAT_PROBE_TEXT);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                return new EndpointTestResult(true, ms, model, null,
                        "连接正常，模型回复：" + abbreviateReply(reply), null);
            }
            List<float[]> vectors = OpenAiCompatEmbeddings.embed(
                    new OpenAiCompatEmbeddings.Options(baseUrl, apiKey, model, timeoutSeconds, 1),
                    List.of(PROBE_TEXT));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int dims = vectors.get(0).length;
            String note = prevDimensions != null && prevDimensions != dims
                    ? "（原记录维度 " + prevDimensions + "）" : "";
            return new EndpointTestResult(true, ms, model, dims,
                    "连接正常，实测维度 " + dims + note, null);
        } catch (ModelCallException ex) {
            // 父类同时覆盖向量与对话两条链（EmbeddingCallException 是它的子类）
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new EndpointTestResult(false, ms, model, null, ex.getMessage(), null);
        }
    }

    /**
     * 决策端点探针（CAP-55 FR-02）：两段实调——先 {@code GET /healthz} 确认边车活着且 checkpoint
     * 已常驻，再发一条固定样例 {@code POST /v1/predict} 实测决策往返。
     *
     * <p>健康检查不过就<b>不再发样例题</b>：白跑一次前向没有意义，而"边车没起来"与"模型答不出来"
     * 是两件事，报错必须能分开。<b>不重试</b>——探针由人盯着（与 CHAT 探针同口径），
     * 运行时调用的一次重试是 {@code HttpDecisionEngine} 的事。</p>
     */
    private static EndpointTestResult probeDecision(String baseUrl, String apiKey, String model,
                                                    int timeoutSeconds, long t0) {
        LayaDecisionClient.Options opt =
                new LayaDecisionClient.Options(baseUrl, apiKey, model, timeoutSeconds);
        LayaDecisionClient.Health health = LayaDecisionClient.healthz(opt);
        if (!health.ok()) {
            return new EndpointTestResult(false, msSince(t0), model, null,
                    "/healthz 返回 status=" + health.status() + "（边车未就绪）", null);
        }
        LayaDecisionClient.Reply reply = LayaDecisionClient.predict(
                opt, LayaDecisionClient.sampleState(), LayaDecisionClient.sampleQuestions());
        long ms = msSince(t0);
        return new EndpointTestResult(true, ms, model, null,
                "连接正常，" + health.summary() + "；样例决策往返 " + ms + " ms："
                        + sampleOutcome(reply) + routingNote(reply), null);
    }

    /** 样例答案摘要（选项 + 概率）：让「边车通」与「模型真的答出了东西」在 message 里可区分 */
    private static String sampleOutcome(LayaDecisionClient.Reply reply) {
        DecisionAnswer answer = reply.answers().get(LayaDecisionClient.SAMPLE_QUESTION_ID);
        if (answer == null) {
            return "样例题无答案（返回了 " + String.join("、", reply.answers().keySet()) + "）";
        }
        Double p = answer.choiceProbability();
        return LayaDecisionClient.SAMPLE_QUESTION_ID + "=" + answer.choice()
                + (p == null ? "" : "（" + Math.round(p * 100) + "%）");
    }

    /** 边车选 checkpoint 的原因：连接测试就该把"为什么用了这个模型"摆出来（多半是语言不匹配） */
    private static String routingNote(LayaDecisionClient.Reply reply) {
        String reason = reply.routingReason();
        return reason == null || reason.isBlank() ? "" : "；routing：" + reason;
    }

    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** 回复摘要：压成一行 + 截断。它会进 UI，也会落 last_test_message，别把千字回复原样塞进去 */
    private static String abbreviateReply(String reply) {
        String oneLine = reply == null ? "" : reply.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= CHAT_REPLY_SNIPPET_LEN
                ? oneLine : oneLine.substring(0, CHAT_REPLY_SNIPPET_LEN) + "…";
    }

    /** 测试结果回写（短事务；dimensions 只在测试成功时更新） */
    private void persistTestResult(long id, EndpointTestResult r, Integer dimensions, boolean updateDimensions) {
        txTemplate.executeWithoutResult(status -> repo.findById(id).ifPresent(e -> {
            e.setLastTestAt(Instant.now());
            e.setLastTestOk(r.ok());
            e.setLastTestMessage(truncate(r.message(), 1000));
            if (updateDimensions && dimensions != null) {
                e.setDimensions(dimensions);
            }
            e.setUpdatedAt(Instant.now());
            repo.save(e);
        }));
    }

    // ---------------- ModelEndpointProvider（FR-04 解析链） ----------------

    /**
     * 库级覆盖（active）→ 平台默认（active）→ empty。
     * 库级端点被停用/删除时不回写数据，只在这里回落——降级语义与 CAP-44 一致，不回落 mock。
     */
    @Override
    public Optional<ModelEndpointView> resolve(Long kbEndpointId) {
        if (kbEndpointId != null) {
            Optional<ModelEndpointView> override = activeEndpoint(kbEndpointId);
            if (override.isPresent()) {
                return override;
            }
            log.debug("库级端点 {} 不可用（停用/已删），回落平台默认端点", kbEndpointId);
        }
        return defaultEndpoint();
    }

    @Override
    public Optional<ModelEndpointView> defaultEndpoint() {
        return defaultEndpoint(ModelEndpointView.KIND_EMBEDDING);
    }

    /**
     * 指定类型的平台默认端点。类型由 kind 常量给出（{@code is_default} 的唯一性由 {@link #setDefault}
     * 在单事务内保证，故"该类型的默认"最多一条）；<b>无默认就返回 empty，不跨类型回落</b>——
     * 向量端点拿来对话、对话端点拿来索引都是必坏的组合。
     */
    @Override
    public Optional<ModelEndpointView> defaultEndpoint(String kind) {
        return repo.findFirstByKindAndIsDefaultTrueAndStatus(kind, ModelEndpointEntity.STATUS_ACTIVE)
                .map(this::toSpiView);
    }

    @Override
    public Optional<ModelEndpointView> activeEndpoint(long id) {
        return repo.findById(id).filter(ModelEndpointEntity::active).map(this::toSpiView);
    }

    // ---------------- 内部 ----------------

    private List<String> usagesOf(long endpointId) {
        List<String> usages = new ArrayList<>();
        usageProviders.orderedStream().forEach(p -> {
            try {
                usages.addAll(p.usagesOf(endpointId));
            } catch (Exception e) {
                // 引用方查询失败不能变成"删得掉"——宁可拒绝删除
                log.warn("端点引用方查询失败: endpoint={} err={}", endpointId, e.toString());
                usages.add("（引用方查询失败，无法确认是否被引用）");
            }
        });
        return usages;
    }

    private ModelEndpointEntity require(long id) {
        return repo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "模型端点不存在: " + id));
    }

    private ModelEndpointView toSpiView(ModelEndpointEntity e) {
        String key = null;
        if (e.getApiKeyEnc() != null && !e.getApiKeyEnc().isBlank()) {
            try {
                key = cipher.decrypt(e.getApiKeyEnc());
            } catch (Exception ex) {
                // 解析路径不能因为凭据坏了就整体 500：放行为"无凭据"，
                // 调用会得到 401 并落到 index_error，用户点「测试连接」能看到确切原因
                log.warn("端点 {} 凭据解密失败，本次按无凭据调用: {}", e.getId(), ex.getMessage());
            }
        }
        return new ModelEndpointView(e.getId(), e.getKind(), e.getProvider(), e.getName(), e.getBaseUrl(),
                key, e.getModel(), e.getDimensions(), e.getTimeoutSeconds(), e.getBatchSize(),
                e.getTopK(), e.getThreshold());
    }

    private static ModelEndpointApiView view(ModelEndpointEntity e) {
        return new ModelEndpointApiView(e.getId(), e.getKind(), e.getName(), e.getProvider(), e.getBaseUrl(),
                e.getModel(), e.getApiKeyEnc() != null && !e.getApiKeyEnc().isBlank(), e.getDimensions(),
                e.getTimeoutSeconds(), e.getBatchSize(), e.getTopK(), e.getThreshold(), e.getStatus(),
                e.isDefault(), e.getLastTestAt(), e.getLastTestOk(), e.getLastTestMessage(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    /** baseUrl / model / 超时 / 批量 / 覆盖项的统一落值（existing 非空表示更新：空值沿用旧值） */
    private void applyTransport(ModelEndpointEntity e, ModelEndpointRequest req, ModelEndpointEntity existing) {
        boolean decision = ModelEndpointEntity.KIND_DECISION.equals(e.getKind());
        String curBaseUrl = existing == null ? "" : nullToEmpty(existing.getBaseUrl());
        String curModel = existing == null ? "" : nullToEmpty(existing.getModel());
        String baseUrl = nullToEmpty(req.baseUrl()).trim();
        String model = nullToEmpty(req.model()).trim();
        if (baseUrl.isEmpty()) {
            baseUrl = curBaseUrl;
        }
        if (model.isEmpty()) {
            model = curModel;
        }
        if (e.mock()) {
            e.setBaseUrl(baseUrl.isEmpty() ? null : baseUrl);
            e.setModel(model.isEmpty() ? null : model);
        } else {
            if (baseUrl.isEmpty()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, decision
                        ? "baseUrl 必填（laya 决策端点只填边车根地址，如 http://host:8377，不带 /v1）"
                        : "baseUrl 必填（openai-compatible 端点）");
            }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "baseUrl 必须是 http/https 地址");
            }
            // 决策端点的 model 是 checkpoint 别名（english/multilingual/typed-decisions），可空 = 边车自己路由
            if (model.isEmpty() && !decision) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "model 必填（openai-compatible 端点）");
            }
            e.setBaseUrl(baseUrl);
            e.setModel(model.isEmpty() ? null : model);
        }
        int curTimeout = existing == null ? DEFAULT_TIMEOUT_SECONDS : existing.getTimeoutSeconds();
        e.setTimeoutSeconds(bounded(req.timeoutSeconds(), curTimeout, 1, 600, "timeoutSeconds"));
        if (!ModelEndpointEntity.KIND_EMBEDDING.equals(e.getKind())) {
            // 对话/决策端点都不吃向量语义：批量/检索参数一律落 null，且不做范围校验——
            // 请求里带了越界 topK 也只当没传（报错会让前端必须为每种类型分叉校验，而这两个字段对
            // CHAT/DECISION 根本没有意义）。update 时同一个实例既是 target 又是 existing，先读旧值再置空即可。
            // batchSize 列 NOT NULL，保留旧值/默认值不使用。
            e.setBatchSize(existing == null ? DEFAULT_BATCH_SIZE : existing.getBatchSize());
            e.setTopK(null);
            e.setThreshold(null);
            return;
        }
        int curBatch = existing == null ? DEFAULT_BATCH_SIZE : existing.getBatchSize();
        e.setBatchSize(bounded(req.batchSize(), curBatch, 1, 256, "batchSize"));
        Integer topK = req.topK() != null ? req.topK() : (existing == null ? null : existing.getTopK());
        if (topK != null && (topK < 1 || topK > 100)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "topK 需在 1~100");
        }
        e.setTopK(topK);
        Double threshold = req.threshold() != null ? req.threshold()
                : (existing == null ? null : existing.getThreshold());
        if (threshold != null && (threshold < 0 || threshold > 1)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "threshold 需在 0~1");
        }
        e.setThreshold(threshold);
    }

    /** 停用默认端点时顺带摘掉 is_default：不让界面显示一个停用端点仍是"平台默认" */
    private void applyStatus(ModelEndpointEntity e, String status) {
        e.setStatus(status);
        if (ModelEndpointEntity.STATUS_DISABLED.equals(status) && e.isDefault()) {
            e.setDefault(false);
            log.warn("平台默认端点 {} [{}] 被停用，默认端点已置空{}",
                    e.getId(), e.getKind(), missingDefaultHint(e.getKind()));
        }
    }

    /**
     * kind 白名单：EMBEDDING（向量化）、CHAT（通用对话）、DECISION（CAP-55 决策引擎）已开放；
     * RERANK 仍预留——没有消费方就开门，只会多出一批"配了没人用"的端点。
     */
    private static String kind(String raw) {
        String k = raw == null || raw.isBlank() ? ModelEndpointEntity.KIND_EMBEDDING : raw.trim().toUpperCase();
        if (!ModelEndpointEntity.KIND_EMBEDDING.equals(k) && !ModelEndpointEntity.KIND_CHAT.equals(k)
                && !ModelEndpointEntity.KIND_DECISION.equals(k)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "kind 目前支持 EMBEDDING / CHAT / DECISION（RERANK 预留，待消费方就绪后开放）");
        }
        return k;
    }

    /**
     * 「没有平台默认端点」的后果按 kind 不同：向量端点缺默认 = 索引降级；对话端点缺默认 =
     * 没显式指定端点的模型问答建不出来（CAP-49 的解析链第二级，落空即 409 不回落）；
     * 决策端点缺默认 = 决策类能力整体降级（CAP-55 FR-06，例如提案分诊退回纯人工）。
     */
    private static String missingDefaultHint(String kind) {
        if (ModelEndpointEntity.KIND_EMBEDDING.equals(kind)) {
            return "，在设置新的默认端点前索引将保持降级";
        }
        if (ModelEndpointEntity.KIND_DECISION.equals(kind)) {
            return "，决策类能力（如知识库提案智能分诊）将保持降级，只走人工路径";
        }
        return "（未指定端点的模型问答将无法新建，需在新建时选择端点）";
    }

    /**
     * provider 白名单<b>按 kind 收口</b>：向量/对话端点走 OpenAI 兼容协议；决策端点走 laya 边车协议。
     * 不按 kind 分开校验就会让"EMBEDDING + laya"这种组合落库，然后在探针里拿兜底路径去打
     * {@code /embeddings}——必错，且错得晚（配的时候不报，测的时候才炸）。
     */
    private static String provider(String kind, String raw) {
        String p = raw == null || raw.isBlank() ? defaultProvider(kind) : raw.trim();
        if (ModelEndpointEntity.PROVIDER_MOCK.equalsIgnoreCase(p)) {
            // mock 与 kind 无关：哪种类型都能用假应答自测（探针不发网络请求）
            return ModelEndpointEntity.PROVIDER_MOCK;
        }
        boolean decision = ModelEndpointEntity.KIND_DECISION.equals(kind);
        if (decision && ModelEndpointEntity.PROVIDER_LAYA.equalsIgnoreCase(p)) {
            return ModelEndpointEntity.PROVIDER_LAYA;
        }
        if (!decision && ModelEndpointEntity.PROVIDER_OPENAI.equalsIgnoreCase(p)) {
            return ModelEndpointEntity.PROVIDER_OPENAI;
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST,
                "provider 只支持 " + (decision ? "laya / mock" : "openai-compatible / mock"));
    }

    private static String defaultProvider(String kind) {
        return ModelEndpointEntity.KIND_DECISION.equals(kind)
                ? ModelEndpointEntity.PROVIDER_LAYA : ModelEndpointEntity.PROVIDER_OPENAI;
    }

    private static String status(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (!ModelEndpointEntity.STATUS_ACTIVE.equals(s) && !ModelEndpointEntity.STATUS_DISABLED.equals(s)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "status 必须是 active 或 disabled");
        }
        return s;
    }

    private static String statusOrDefault(String raw) {
        return raw == null || raw.isBlank() ? ModelEndpointEntity.STATUS_ACTIVE : status(raw);
    }

    private static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "name 不能为空");
        }
        return raw.trim();
    }

    private static int bounded(Integer value, int current, int min, int max, String field) {
        int v = value != null ? value : current;
        if (v < min || v > max) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, field + " 需在 " + min + "~" + max);
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
