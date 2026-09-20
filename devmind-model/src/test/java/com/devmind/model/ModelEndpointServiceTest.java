package com.devmind.model;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.model.ModelEndpointUsageProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.model.config.EmbeddingSeedProperties;
import com.devmind.model.config.ModelProperties;
import com.devmind.model.dto.ModelEndpointApiView;
import com.devmind.model.dto.ModelEndpointRequest;
import com.devmind.model.repo.ModelEndpointRepository;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-48 FR-01/04 端点服务：字段校验、凭据"留空不改"、默认端点唯一、删除保护（含引用方查询
 * 失败时的 fail-closed 行为）、解析链四级回落。repo 用 mock，不拉 Spring 上下文。
 */
class ModelEndpointServiceTest {

    private ModelEndpointRepository repo;
    private ModelCipher cipher;
    private ModelEndpointService service;
    private ModelEndpointUsageProvider usageProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ModelProperties props = new ModelProperties();
        props.setCryptoKey("test-master-key");
        cipher = new ModelCipher(props);
        cipher.init();

        repo = mock(ModelEndpointRepository.class);
        usageProvider = mock(ModelEndpointUsageProvider.class);
        ObjectProvider<ModelEndpointUsageProvider> usages = mock(ObjectProvider.class);
        lenient().when(usages.orderedStream()).thenReturn(Stream.of(usageProvider));
        lenient().when(usageProvider.usagesOf(anyLong())).thenReturn(List.of());

        // 真 TransactionTemplate + 桩事务管理器：让 test() 的探测写回真的执行到
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = new ModelEndpointService(repo, cipher, new EmbeddingSeedProperties(),
                new TransactionTemplate(tm), usages);
        lenient().when(repo.save(any())).thenAnswer(inv -> {
            ModelEndpointEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
    }

    /** 造一个已存端点并挂到 repo.findById 上；返回实体便于断言其被就地改写 */
    private ModelEndpointEntity stored(long id, String provider, boolean isDefault, String status) {
        ModelEndpointEntity e = new ModelEndpointEntity();
        e.setId(id);
        e.setKind(ModelEndpointEntity.KIND_EMBEDDING);
        e.setName("端点" + id);
        e.setProvider(provider);
        e.setModel("bge-m3");
        e.setBaseUrl(ModelEndpointEntity.PROVIDER_MOCK.equals(provider)
                ? null : "https://api.example.com/v1");
        e.setStatus(status);
        e.setDefault(isDefault);
        e.setTimeoutSeconds(30);
        e.setBatchSize(32);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        lenient().when(repo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    // ---------------- 字段校验 ----------------

    @Test
    void rejectsKindOtherThanEmbedding() {
        ModelEndpointRequest req = new ModelEndpointRequest("CHAT", "聊天端点", "openai-compatible",
                "https://api.example.com/v1", "sk-abc", "gpt", null, null, null, null, null);

        DevMindException ex = assertThrows(DevMindException.class, () -> service.create(req));
        assertTrue(ex.getMessage().contains("EMBEDDING"), ex.getMessage());
    }

    @Test
    void rejectsUnknownProvider() {
        ModelEndpointRequest req = new ModelEndpointRequest("EMBEDDING", "X", "ollama",
                "https://api.example.com/v1", null, "bge", null, null, null, null, null);

        assertThrows(DevMindException.class, () -> service.create(req));
    }

    @Test
    void rejectsBlankName() {
        ModelEndpointRequest req = new ModelEndpointRequest("EMBEDDING", "  ", "mock",
                null, null, null, null, null, null, null, null);

        assertTrue(assertThrows(DevMindException.class, () -> service.create(req))
                .getMessage().contains("name"));
    }

    @Test
    void openAiRequiresBaseUrlThenModelThenHttpScheme() {
        ModelEndpointRequest noUrl = new ModelEndpointRequest("EMBEDDING", "X", "openai-compatible",
                "", null, "bge", null, null, null, null, null);
        assertTrue(assertThrows(DevMindException.class, () -> service.create(noUrl))
                .getMessage().contains("baseUrl"));

        ModelEndpointRequest noModel = new ModelEndpointRequest("EMBEDDING", "X", "openai-compatible",
                "https://api.example.com/v1", null, "", null, null, null, null, null);
        assertTrue(assertThrows(DevMindException.class, () -> service.create(noModel))
                .getMessage().contains("model"));

        ModelEndpointRequest badScheme = new ModelEndpointRequest("EMBEDDING", "X", "openai-compatible",
                "file:///etc/passwd", null, "bge", null, null, null, null, null);
        assertTrue(assertThrows(DevMindException.class, () -> service.create(badScheme))
                .getMessage().contains("http"));
    }

    @Test
    void rejectsOutOfRangeTimeoutAndBatch() {
        ModelEndpointRequest slow = new ModelEndpointRequest("EMBEDDING", "X", "openai-compatible",
                "https://api.example.com/v1", null, "bge", 9999, null, null, null, null);
        assertTrue(assertThrows(DevMindException.class, () -> service.create(slow))
                .getMessage().contains("timeoutSeconds"));

        ModelEndpointRequest zeroBatch = new ModelEndpointRequest("EMBEDDING", "X", "openai-compatible",
                "https://api.example.com/v1", null, "bge", null, 0, null, null, null);
        assertTrue(assertThrows(DevMindException.class, () -> service.create(zeroBatch))
                .getMessage().contains("batchSize"));

        ModelEndpointRequest badTopK = new ModelEndpointRequest("EMBEDDING", "X", "mock",
                null, null, null, null, null, 0, null, null);
        assertTrue(assertThrows(DevMindException.class, () -> service.create(badTopK))
                .getMessage().contains("topK"));

        ModelEndpointRequest badThreshold = new ModelEndpointRequest("EMBEDDING", "X", "mock",
                null, null, null, null, null, null, 1.5, null);
        assertTrue(assertThrows(DevMindException.class, () -> service.create(badThreshold))
                .getMessage().contains("threshold"));
    }

    @Test
    void rejectsUnknownStatus() {
        ModelEndpointRequest req = new ModelEndpointRequest("EMBEDDING", "X", "mock",
                null, null, null, null, null, null, null, "paused");

        assertTrue(assertThrows(DevMindException.class, () -> service.create(req))
                .getMessage().contains("status"));
    }

    @Test
    void mockProviderNeedsNoTransportAndReportsNoDimensionsUntilProbed() {
        ModelEndpointRequest req = new ModelEndpointRequest("EMBEDDING", "内置 Mock", "mock",
                null, null, null, null, null, null, null, null);

        ModelEndpointApiView view = service.create(req);

        assertEquals(ModelEndpointEntity.PROVIDER_MOCK, view.provider());
        assertNull(view.baseUrl());
        assertNull(view.dimensions(), "维度只能由连接测试探测写入，新建时必须为空");
        assertFalse(view.hasApiKey());
        assertEquals(ModelEndpointEntity.STATUS_ACTIVE, view.status(), "未传 status 应落 active");
        assertFalse(view.isDefault(), "新建端点不应自动成为平台默认");
    }

    // ---------------- 凭据与局部更新 ----------------

    @Test
    void updateKeepsExistingApiKeyWhenBlank() {
        ModelEndpointEntity e = stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false,
                ModelEndpointEntity.STATUS_ACTIVE);
        e.setApiKeyEnc(cipher.encrypt("sk-original"));

        ModelEndpointApiView view = service.update(1L, new ModelEndpointRequest("EMBEDDING", "改名",
                "openai-compatible", "https://api.example.com/v1", "   ", "bge-m3",
                null, null, null, null, null));

        assertTrue(view.hasApiKey());
        assertEquals("sk-original", cipher.decrypt(e.getApiKeyEnc()), "apiKey 留空应保持原密钥不变");
        assertEquals("改名", view.name());
    }

    @Test
    void updateReplacesApiKeyWhenProvided() {
        ModelEndpointEntity e = stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false,
                ModelEndpointEntity.STATUS_ACTIVE);
        e.setApiKeyEnc(cipher.encrypt("sk-original"));

        service.update(1L, new ModelEndpointRequest("EMBEDDING", null, null,
                null, "sk-new", null, null, null, null, null, null));

        assertEquals("sk-new", cipher.decrypt(e.getApiKeyEnc()));
    }

    @Test
    void updateKeepsTransportAndAppliesOverridesWhenBlank() {
        ModelEndpointEntity e = stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false,
                ModelEndpointEntity.STATUS_ACTIVE);

        ModelEndpointApiView view = service.update(1L, new ModelEndpointRequest("EMBEDDING", null,
                null, null, null, null, 90, 8, 5, 0.3, null));

        assertEquals("https://api.example.com/v1", view.baseUrl(), "baseUrl 留空应沿用旧值");
        assertEquals("bge-m3", view.model(), "model 留空应沿用旧值");
        assertEquals(90, view.timeoutSeconds());
        assertEquals(8, view.batchSize());
        assertEquals(5, view.topK());
        assertEquals(0.3, view.threshold());
    }

    @Test
    void rejectsKindChangeOnUpdate() {
        stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false, ModelEndpointEntity.STATUS_ACTIVE);

        ModelEndpointRequest req = new ModelEndpointRequest("CHAT", "X", null, null, null, null,
                null, null, null, null, null);

        assertThrows(DevMindException.class, () -> service.update(1L, req));
    }

    @Test
    void updateOnMissingEndpointIs404() {
        DevMindException ex = assertThrows(DevMindException.class,
                () -> service.update(404L, new ModelEndpointRequest(null, "X", null, null, null,
                        null, null, null, null, null, null)));

        assertEquals(404, ex.getErrorCode().getStatus());
    }

    // ---------------- 平台默认唯一性 ----------------

    @Test
    void setDefaultClearsOldDefaultInSameTransaction() {
        stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false, ModelEndpointEntity.STATUS_ACTIVE);

        ModelEndpointApiView view = service.setDefault(1L);

        verify(repo).clearDefaultExcept(eq(ModelEndpointEntity.KIND_EMBEDDING), eq(1L), any(Instant.class));
        assertTrue(view.isDefault());
    }

    @Test
    void setDefaultRejectsDisabledEndpoint() {
        stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false, ModelEndpointEntity.STATUS_DISABLED);

        assertThrows(DevMindException.class, () -> service.setDefault(1L));
        verify(repo, never()).clearDefaultExcept(anyString(), anyLong(), any(Instant.class));
    }

    @Test
    void disablingDefaultEndpointClearsDefaultFlag() {
        ModelEndpointEntity e = stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, true,
                ModelEndpointEntity.STATUS_ACTIVE);

        ModelEndpointApiView view = service.changeStatus(1L, "disabled");

        assertEquals(ModelEndpointEntity.STATUS_DISABLED, view.status());
        assertFalse(view.isDefault(), "停用的端点不应继续显示为平台默认");
        assertFalse(e.isDefault());
    }

    // ---------------- 删除保护 ----------------

    @Test
    void deleteRejectedWhenReferenced() {
        stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false, ModelEndpointEntity.STATUS_ACTIVE);
        when(usageProvider.usagesOf(1L)).thenReturn(List.of("知识库：前端规范", "知识库：运维手册"));

        DevMindException ex = assertThrows(DevMindException.class, () -> service.delete(1L));

        assertEquals(409, ex.getErrorCode().getStatus());
        assertTrue(ex.getMessage().contains("前端规范"), ex.getMessage());
        verify(repo, never()).delete(any());
    }

    @Test
    void deletePassesWhenUnreferenced() {
        ModelEndpointEntity e = stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false,
                ModelEndpointEntity.STATUS_ACTIVE);

        service.delete(1L);

        verify(repo).delete(e);
    }

    @Test
    void deleteFailsClosedWhenUsageLookupBreaks() {
        stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, false, ModelEndpointEntity.STATUS_ACTIVE);
        when(usageProvider.usagesOf(1L)).thenThrow(new IllegalStateException("db down"));

        // 引用方查询失败不能变成"删得掉"——否则一次数据库抖动就能删掉正在被引用的端点
        assertThrows(DevMindException.class, () -> service.delete(1L));
        verify(repo, never()).delete(any());
    }

    // ---------------- FR-04 解析链 ----------------

    @Test
    void resolvePrefersKbOverride() {
        stored(7L, ModelEndpointEntity.PROVIDER_MOCK, false, ModelEndpointEntity.STATUS_ACTIVE);

        Optional<ModelEndpointView> resolved = service.resolve(7L);

        assertTrue(resolved.isPresent());
        assertEquals(7L, resolved.get().id());
    }

    @Test
    void resolveFallsBackToPlatformDefaultWhenOverrideDisabled() {
        stored(7L, ModelEndpointEntity.PROVIDER_MOCK, false, ModelEndpointEntity.STATUS_DISABLED);
        ModelEndpointEntity def = stored(9L, ModelEndpointEntity.PROVIDER_OPENAI, true,
                ModelEndpointEntity.STATUS_ACTIVE);
        when(repo.findFirstByKindAndIsDefaultTrueAndStatus(
                ModelEndpointEntity.KIND_EMBEDDING, ModelEndpointEntity.STATUS_ACTIVE))
                .thenReturn(Optional.of(def));

        Optional<ModelEndpointView> resolved = service.resolve(7L);

        assertEquals(9L, resolved.orElseThrow().id(), "库级端点停用应回落平台默认");
    }

    @Test
    void resolveReturnsEmptyWhenNothingUsable() {
        when(repo.findFirstByKindAndIsDefaultTrueAndStatus(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertTrue(service.resolve(null).isEmpty(), "无默认端点时必须返回 empty（由调用方降级，不回落 mock）");
        assertTrue(service.defaultEndpoint().isEmpty());
        assertTrue(service.activeEndpoint(404L).isEmpty());
    }

    // ---------------- SPI 视图与密钥暴露面 ----------------

    @Test
    void spiViewCarriesDecryptedKeyButHttpViewNeverDoes() {
        ModelEndpointEntity e = stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, true,
                ModelEndpointEntity.STATUS_ACTIVE);
        e.setApiKeyEnc(cipher.encrypt("sk-live"));

        assertEquals("sk-live", service.activeEndpoint(1L).orElseThrow().apiKey(),
                "SPI 视图需要明文凭据才能真正发起调用");

        assertTrue(service.get(1L).hasApiKey());
        List<String> names = Arrays.stream(ModelEndpointApiView.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertFalse(names.contains("apiKey"), "HTTP 视图不得有任何明文密钥字段: " + names);
        assertFalse(names.contains("apiKeyEnc"), "HTTP 视图不得回显密文: " + names);
    }

    @Test
    void spiViewSilentlyOmitsBrokenCredential() {
        ModelEndpointEntity e = stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, true,
                ModelEndpointEntity.STATUS_ACTIVE);
        e.setApiKeyEnc("enc1:not-a-valid-ciphertext");

        // 凭据坏了不能让解析路径整体炸成 500；按无凭据调用，调用侧会落到 index_error，
        // 用户点「测试连接」能看到确切原因
        assertNull(service.activeEndpoint(1L).orElseThrow().apiKey());
    }

    @Test
    void spiViewExposesTransportOverridesForCallers() {
        ModelEndpointEntity e = stored(1L, ModelEndpointEntity.PROVIDER_OPENAI, true,
                ModelEndpointEntity.STATUS_ACTIVE);
        e.setTopK(7);
        e.setThreshold(0.42);

        ModelEndpointView v = service.activeEndpoint(1L).orElseThrow();

        assertEquals(7, v.topK());
        assertEquals(0.42, v.threshold());
        assertEquals(30, v.timeoutSeconds());
        assertEquals(32, v.batchSize());
        assertFalse(v.mock());
    }
}
