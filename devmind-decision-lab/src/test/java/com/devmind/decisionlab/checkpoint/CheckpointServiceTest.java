package com.devmind.decisionlab.checkpoint;

import com.devmind.auth.IdentityService;
import com.devmind.common.decision.DecisionGate;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.decisionlab.checkpoint.dto.CheckpointGateView;
import com.devmind.decisionlab.checkpoint.dto.CheckpointRequest;
import com.devmind.decisionlab.checkpoint.dto.CheckpointVerifyRequest;
import com.devmind.decisionlab.checkpoint.dto.CheckpointView;
import com.devmind.decisionlab.checkpoint.dto.ServeCheckResult;
import com.devmind.decisionlab.checkpoint.model.DecisionCheckpointEntity;
import com.devmind.decisionlab.checkpoint.repo.DecisionCheckpointRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-56 FR-06/FR-07 服务层 + 闸门：把「谁能放行、凭什么放行、放行后怎么收回」测成可证伪的断言。
 *
 * <p>这里最要紧的两条：
 * <ul>
 *   <li><b>新登记的产物绝不放行</b>（{@link #registeringDoesNotOpenTheGateByItself}）——闸门要是漏了
 *       这一步，整个 CAP 就白做了：模型能被调用、而没有任何人看过它准不准，正是 2026-09-22 那次
 *       退化的形状；</li>
 *   <li><b>撤销后闸门立即关闭</b>、同槽位互斥把旧的顶下来——「现在放行的是谁」必须唯一且随时可查。</li>
 * </ul>
 *
 * <p>仓储照 {@code DatasetServiceTest} 的先例手搓一层内存实现（Boot 4.1.1 没有 JPA 切片）。
 * 闸门用<b>真的</b> {@link CheckpointGate} 接同一份内存仓储，而不是 mock 掉 SPI——
 * 「闸门会不会因为库里多了一行就放行」这件事，只有把两边接起来才测得到。</p>
 */
class CheckpointServiceTest {

    private static final String SLOT = "multilingual";
    private static final String FINGERPRINT = "a".repeat(64);

    private final DecisionCheckpointRepository repo = mock(DecisionCheckpointRepository.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final ServeCheckService serveCheck = mock(ServeCheckService.class);
    private final CheckpointJson json = new CheckpointJson(new ObjectMapper());

    private final Map<Long, DecisionCheckpointEntity> rows = new LinkedHashMap<>();
    private long seq;

    private CheckpointService service;

    @BeforeEach
    void setUp() {
        when(identity.currentActor()).thenReturn("tester");
        when(repo.save(any(DecisionCheckpointEntity.class))).thenAnswer(inv -> {
            DecisionCheckpointEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(++seq);
            }
            rows.put(e.getId(), e);
            return e;
        });
        when(repo.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0))));
        when(repo.findByName(anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(e -> inv.getArgument(0).equals(e.getName())).findFirst());
        when(repo.findByVerifiedTrueOrderByIdAsc()).thenAnswer(inv -> rows.values().stream()
                .filter(DecisionCheckpointEntity::isVerified)
                .sorted(Comparator.comparing(DecisionCheckpointEntity::getId)).toList());
        when(repo.findByServeSlotAndVerifiedTrueOrderByIdAsc(anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(e -> inv.getArgument(0).equals(e.getServeSlot()))
                .filter(DecisionCheckpointEntity::isVerified)
                .sorted(Comparator.comparing(DecisionCheckpointEntity::getId)).toList());
        when(repo.findAllByOrderByIdDesc(any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            List<DecisionCheckpointEntity> all = sortedDesc();
            int from = Math.min((int) pageable.getOffset(), all.size());
            int to = Math.min(from + pageable.getPageSize(), all.size());
            return new PageImpl<>(all.subList(from, to), pageable, all.size());
        });
        when(repo.findByServeSlotOrderByIdDesc(anyString(), any(Pageable.class))).thenAnswer(inv -> {
            String slot = inv.getArgument(0);
            Pageable pageable = inv.getArgument(1);
            List<DecisionCheckpointEntity> hit = sortedDesc().stream()
                    .filter(e -> slot.equals(e.getServeSlot())).toList();
            return new PageImpl<>(hit, pageable, hit.size());
        });
        doAnswer(inv -> {
            rows.remove(((DecisionCheckpointEntity) inv.getArgument(0)).getId());
            return null;
        }).when(repo).delete(any(DecisionCheckpointEntity.class));

        service = new CheckpointService(repo, json, identity, serveCheck, gateProvider());
    }

    // ---------------- 登记 ----------------

    @Test
    void registeringDoesNotOpenTheGateByItself() {
        // 登记是登记、放行是放行，两件事之间必须隔着一个人
        service.create(base("laya-multilingual-v1"));
        assertFalse(service.gate().open(), "刚登记的产物不该放行：闸门要等人工验证");
    }

    @Test
    void registrationRejectsMissingNameSlotKindAndSource() {
        assertEquals("产物名不能为空",
                failure(() -> create("", SLOT, "BASE", "org/m"), ErrorCode.BAD_REQUEST).getMessage());
        assertTrue(failure(() -> create("n", "  ", "BASE", "org/m"), ErrorCode.BAD_REQUEST)
                .getMessage().contains("服务槽位不能为空"));
        assertTrue(failure(() -> create("n", SLOT, "GGUF", "org/m"), ErrorCode.BAD_REQUEST)
                .getMessage().contains("kind 只能是 BASE"));
        assertTrue(failure(() -> create("n", SLOT, "BASE", null), ErrorCode.BAD_REQUEST)
                .getMessage().contains("来源路径不能为空"));
    }

    @Test
    void duplicateNameIsRefusedSoHistoryKeepsPointingAtOneArtifact() {
        service.create(base("laya-multilingual-v1"));
        assertTrue(failure(() -> service.create(base("laya-multilingual-v1")), ErrorCode.CONFLICT)
                .getMessage().contains("换个名字"));
    }

    @Test
    void finetunedArtifactMustCarryASha256AtRegistration() {
        // 自己训出来的东西一定算得出指纹（就在节点上那个目录里），没有指纹就没法回答
        // "放行的是哪一份"——输出目录会被下一轮训练就地覆盖
        CheckpointRequest req = new CheckpointRequest("laya-rlcd-1", SLOT,
                DecisionCheckpointEntity.KIND_FINETUNED, "/apusic/laya/rlcd-1", "node-1",
                "/apusic/laya/rlcd-1", 1_048_576L, null, null, null);
        assertTrue(failure(() -> service.create(req), ErrorCode.BAD_REQUEST)
                .getMessage().contains("sha256"));
    }

    @Test
    void sha256IsValidatedAndNormalizedToLowercase() {
        assertTrue(failure(() -> create("x", SLOT, "BASE", "org/m", "nothex"), ErrorCode.BAD_REQUEST)
                .getMessage().contains("64 位十六进制"));
        CheckpointView v = service.create(new CheckpointRequest("y", SLOT, "BASE", "org/m", null, null, null,
                FINGERPRINT.toUpperCase(Locale.ROOT), null, null));
        assertEquals(FINGERPRINT, v.fingerprintSha256(),
                "指纹统一小写：算的时候各家大小写不一，比对时必须同口径");
    }

    // ---------------- 闸门 ----------------

    @Test
    void gateBlocksWithAnActionableReasonWhenNothingIsRegistered() {
        CheckpointGateView gate = service.gate();
        assertFalse(gate.open());
        assertTrue(gate.reason().contains("决策实验室"), "被拦住时要说清下一步去哪儿做：" + gate.reason());
        assertTrue(gate.reason().contains("验证通过"), gate.reason());
    }

    @Test
    void gateNamesTheLatestArtifactWhenRegisteredButUnverified() {
        service.create(base("laya-multilingual-v1"));
        service.create(base("laya-multilingual-v2"));
        CheckpointGateView gate = service.gate();
        assertFalse(gate.open());
        assertTrue(gate.reason().contains("已登记 2 份"), gate.reason());
        assertTrue(gate.reason().contains("laya-multilingual-v2"), "要说最近那份叫什么：" + gate.reason());
    }

    @Test
    void gateOpensAfterVerificationAndClosesAgainOnRevoke() {
        CheckpointView v = service.create(base("laya-multilingual-v1"));
        service.verify(v.id(), new CheckpointVerifyRequest("基准集 60 题，ECE 0.09，优于多数类基线", null));

        CheckpointGateView open = service.gate();
        assertTrue(open.open(), "验证通过后应放行");
        assertEquals(1, open.serving().size());
        assertEquals(v.id(), open.serving().get(0).id());

        service.unverify(v.id(), new CheckpointVerifyRequest(null, "基准集标错了三条"));
        assertFalse(service.gate().open(), "撤销后必须立即关闭（引擎每次询问都重新查库，无缓存）");
    }

    // ---------------- 验证 ----------------

    @Test
    void verificationDemandsWrittenEvidence() {
        CheckpointView v = service.create(base("laya-multilingual-v1"));
        assertTrue(failure(() -> service.verify(v.id(), new CheckpointVerifyRequest("   ", null)),
                ErrorCode.BAD_REQUEST).getMessage().contains("判断依据"));
        assertTrue(failure(() -> service.verify(v.id(), null), ErrorCode.BAD_REQUEST)
                .getMessage().contains("判断依据"));
    }

    @Test
    void verificationRequiresSomethingThatIdentifiesTheArtifact() {
        // 直接落一行"既无来源又无指纹"的基础模型：登记接口拦得住，但存量行与将来的内部写入路径拦不住
        DecisionCheckpointEntity blind = newRow("blind", SLOT, DecisionCheckpointEntity.KIND_BASE);
        blind.setSourcePath(null);
        blind.setFingerprintPath(null);
        rows.put(blind.getId(), blind);

        assertTrue(failure(() -> service.verify(blind.getId(),
                new CheckpointVerifyRequest("看过指标了", null)), ErrorCode.CONFLICT)
                .getMessage().contains("从哪来"));
    }

    @Test
    void verificationOfAMislabeledFinetunedRowIsRefusedEvenIfItSlippedIn() {
        DecisionCheckpointEntity ft = newRow("sneaky", SLOT, DecisionCheckpointEntity.KIND_FINETUNED);
        ft.setSourcePath("/apusic/laya/sneaky");
        ft.setFingerprintSha256(null);
        rows.put(ft.getId(), ft);

        assertTrue(failure(() -> service.verify(ft.getId(),
                new CheckpointVerifyRequest("指标很好", null)), ErrorCode.CONFLICT)
                .getMessage().contains("哪一份"));
    }

    @Test
    void verifyingAnotherArtifactInTheSameSlotTakesOverTheSlot() {
        // 边车一个槽位只加载一份权重："现在放行的是谁"必须唯一
        CheckpointView first = service.create(base("laya-multilingual-v1"));
        service.verify(first.id(), new CheckpointVerifyRequest("v1 指标", null));
        CheckpointView second = service.create(base("laya-multilingual-v2"));
        service.verify(second.id(), new CheckpointVerifyRequest("v2 指标更好", null));

        List<CheckpointView> serving = service.gate().serving();
        assertEquals(1, serving.size(), "同槽位不该有两份同时放行");
        assertEquals(second.id(), serving.get(0).id());

        CheckpointView old = service.detail(first.id()).checkpoint();
        assertFalse(old.verified(), "被顶下来的那份要关掉开关");
        assertEquals("tester", old.verifiedBy(),
                "但「谁验过它」这段历史要留着——追责时只剩「它曾经绿过」没用");
    }

    @Test
    void verifyTwiceOnTheSameArtifactPointsAtRevokeFirst() {
        CheckpointView v = service.create(base("laya-multilingual-v1"));
        service.verify(v.id(), new CheckpointVerifyRequest("第一版依据", null));
        assertTrue(failure(() -> service.verify(v.id(), new CheckpointVerifyRequest("想改依据", null)),
                ErrorCode.CONFLICT).getMessage().contains("撤销放行"));
    }

    @Test
    void revokingKeepsTheVerificationHistoryAndAppendsTheReason() {
        CheckpointView v = service.create(base("laya-multilingual-v1"));
        service.verify(v.id(), new CheckpointVerifyRequest("基准集 60 题通过", null));
        CheckpointView after = service.unverify(v.id(),
                new CheckpointVerifyRequest(null, "换了 T4 权重，需重验"));

        assertFalse(after.verified());
        assertEquals("tester", after.verifiedBy());
        assertTrue(after.verifiedNote().contains("基准集 60 题通过"),
                "原依据不能被覆盖：" + after.verifiedNote());
        assertTrue(after.verifiedNote().contains("换了 T4 权重，需重验"),
                "撤销原因要落库：" + after.verifiedNote());
    }

    @Test
    void revokeRequiresAReasonAndRefusesWhenNotServing() {
        CheckpointView v = service.create(base("laya-multilingual-v1"));
        assertTrue(failure(() -> service.unverify(v.id(), new CheckpointVerifyRequest(null, "随便写写")),
                ErrorCode.CONFLICT).getMessage().contains("并未在放行"));

        service.verify(v.id(), new CheckpointVerifyRequest("依据", null));
        assertTrue(failure(() -> service.unverify(v.id(), new CheckpointVerifyRequest(null, " ")),
                ErrorCode.BAD_REQUEST).getMessage().contains("必须写明原因"));
    }

    // ---------------- 删除 ----------------

    @Test
    void deletingAServingArtifactIsRefusedButUnverifiedOnesCanGo() {
        CheckpointView serving = service.create(base("laya-multilingual-v1"));
        service.verify(serving.id(), new CheckpointVerifyRequest("依据", null));
        assertTrue(failure(() -> service.delete(serving.id()), ErrorCode.CONFLICT)
                .getMessage().contains("先撤销验证"));

        CheckpointView draft = service.create(base("laya-multilingual-v2"));
        service.delete(draft.id());
        assertEquals(1, rows.size(), "未验证的行随便删");
    }

    // ---------------- serve 自检 ----------------

    @Test
    void serveCheckPersistsStatusAndTheFullReport() {
        CheckpointView v = service.create(base("laya-multilingual-v1"));
        when(serveCheck.run(any(DecisionCheckpointEntity.class))).thenReturn(ServeCheckResult.of(
                List.of(new ServeCheckResult.Check("槽位常驻", ServeCheckResult.FAIL, "常驻清单不含 " + SLOT)),
                Map.of("loaded", List.of("english")), Instant.now()));

        assertEquals(ServeCheckResult.FAIL, service.serveCheck(v.id()).status());
        assertEquals(ServeCheckResult.FAIL, service.detail(v.id()).checkpoint().serveCheckStatus(),
                "结论状态冗余一列给列表页用");

        Map<String, Object> check = service.detail(v.id()).serveCheck();
        assertEquals("常驻清单不含 " + SLOT,
                ((Map<?, ?>) ((List<?>) check.get("checks")).get(0)).get("detail").toString(),
                "逐项明细要能读回来：详情页第一件事就是显示它");
        assertEquals(List.of("english"),
                ((Map<?, ?>) check.get("report")).get("loaded"),
                "边车原值（到底加载了什么）也留着——排错时看的就是它");
    }

    @Test
    void aBrokenReportDoesNotBlowUpTheDetailPage() {
        DecisionCheckpointEntity e = newRow("dirty", SLOT, DecisionCheckpointEntity.KIND_BASE);
        e.setMetricsJson("{不是 JSON");
        rows.put(e.getId(), e);
        assertEquals(Map.of(), service.detail(e.getId()).metrics(), "读不出来的报告按空处理，不该 500");
    }

    // ---------------- 列表 ----------------

    @Test
    void listCanBeNarrowedToOneServingSlot() {
        service.create(base("laya-multilingual-v1"));
        service.create(new CheckpointRequest("laya-english-v1", "english", "BASE", "org/m",
                null, null, null, null, null, null));

        assertEquals(2, service.list(null, 0, 20).total());
        assertEquals(1, service.list("english", 0, 20).total());
        assertEquals("laya-english-v1", service.list("english", 0, 20).items().get(0).name());
    }

    // ---------------- 小工具 ----------------

    private static CheckpointRequest base(String name) {
        return new CheckpointRequest(name, SLOT, DecisionCheckpointEntity.KIND_BASE,
                "hf/laya-multilingual", "node-1", "/models/laya-multilingual", 900_000_000L, null, null, null);
    }

    private CheckpointView create(String name, String slot, String kind, String source) {
        return create(name, slot, kind, source, null);
    }

    private CheckpointView create(String name, String slot, String kind, String source, String sha) {
        return service.create(new CheckpointRequest(name, slot, kind, source, null, null, null, sha, null, null));
    }

    /** 绕过服务层直接造一行（测存量行 / 将来的内部写入路径这些登记接口管不到的入口） */
    private DecisionCheckpointEntity newRow(String name, String slot, String kind) {
        DecisionCheckpointEntity e = new DecisionCheckpointEntity();
        e.setId(++seq);
        e.setName(name);
        e.setServeSlot(slot);
        e.setKind(kind);
        e.setVerified(Boolean.FALSE);
        e.setCreatedBy("tester");
        e.setCreatedAt(Instant.now());
        return e;
    }

    /** 闸门用真实现接同一份内存仓储：这样它才会因为"库里多了一行 verified"而改变行为 */
    @SuppressWarnings("unchecked")
    private ObjectProvider<DecisionGate> gateProvider() {
        ObjectProvider<DecisionGate> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(new CheckpointGate(repo)));
        return provider;
    }

    private List<DecisionCheckpointEntity> sortedDesc() {
        return rows.values().stream()
                .sorted(Comparator.comparing(DecisionCheckpointEntity::getId).reversed()).toList();
    }

    private static DevMindException failure(Runnable action, ErrorCode expected) {
        DevMindException e = assertThrows(DevMindException.class, action::run);
        assertEquals(expected, e.getErrorCode(), e.getMessage());
        return e;
    }
}
