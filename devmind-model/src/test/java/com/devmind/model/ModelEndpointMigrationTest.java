package com.devmind.model;

import com.devmind.common.model.ModelEndpointView;
import com.devmind.model.config.EmbeddingSeedProperties;
import com.devmind.model.config.ModelProperties;
import com.devmind.model.repo.ModelEndpointRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAP-48 FR-07 配置迁移：三条分支 + 幂等（已有端点时绝不重写，否则用户在 UI 的修改每次重启被打回）。
 * 这是 CAP-44/45/46 E2E 零改造升级的支点。
 */
class ModelEndpointMigrationTest {

    private ModelEndpointRepository repo;
    private ModelCipher cipher;
    private EmbeddingSeedProperties seed;
    private ModelEndpointMigration migration;
    private final List<ModelEndpointEntity> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(ModelEndpointRepository.class);
        ModelProperties props = new ModelProperties();
        props.setCryptoKey("test-master-key");
        cipher = new ModelCipher(props);
        cipher.init();
        seed = new EmbeddingSeedProperties();
        migration = new ModelEndpointMigration(repo, cipher, seed);

        when(repo.save(any())).thenAnswer(inv -> {
            ModelEndpointEntity e = inv.getArgument(0);
            saved.add(e);
            return e;
        });
    }

    @Test
    void mockProviderSeedsDefaultMockEndpoint() {
        seed.setProvider("mock");

        migration.run(null);

        assertEquals(1, saved.size());
        ModelEndpointEntity e = saved.get(0);
        assertEquals(ModelEndpointEntity.PROVIDER_MOCK, e.getProvider());
        assertEquals(ModelEndpointMigration.MOCK_ENDPOINT_NAME, e.getName());
        assertEquals(ModelEndpointView.MODEL_MOCK, e.getModel());
        assertEquals(64, e.getDimensions(), "mock 需自报维度，否则索引写入时无法判定维度一致性");
        assertTrue(e.isDefault());
        assertTrue(e.active());
        assertEquals(30, e.getTimeoutSeconds(), "未显式配置时走实体默认值");
    }

    @Test
    void mockDimensionsFollowSeedAndKeepFloorOfEight() {
        seed.setProvider("mock");
        seed.setDimensions(4);

        migration.run(null);

        // MockEmbeddingClient 内部 Math.max(8, dimensions)——种子维度必须与之一致，
        // 否则迁移出来的端点自报维度与实际向量长度不符，索引会全量判为维度失配
        assertEquals(8, saved.get(0).getDimensions());
    }

    @Test
    void openAiSeedSeedsEncryptedDefaultEndpointWithoutDimensions() {
        seed.setBaseUrl("https://api.openai.com/v1");
        seed.setModel("text-embedding-3-small");
        seed.setApiKey("sk-abcdef");

        migration.run(null);

        assertEquals(1, saved.size());
        ModelEndpointEntity e = saved.get(0);
        assertEquals(ModelEndpointEntity.PROVIDER_OPENAI, e.getProvider());
        assertEquals(ModelEndpointMigration.DEFAULT_ENDPOINT_NAME, e.getName());
        assertEquals("text-embedding-3-small", e.getModel());
        assertTrue(cipher.isEncrypted(e.getApiKeyEnc()), "种子密钥必须加密落库，不能明文进表");
        assertEquals("sk-abcdef", cipher.decrypt(e.getApiKeyEnc()));
        assertNull(e.getDimensions(), "维度留空待实测，配置里的猜测不能当事实落库");
        assertTrue(e.isDefault());
    }

    @Test
    void openAiSeedWithoutKeyStillSeedsEndpoint() {
        seed.setBaseUrl("http://127.0.0.1:11434/v1");
        seed.setModel("bge-m3");

        migration.run(null);

        assertEquals(1, saved.size());
        assertNull(saved.get(0).getApiKeyEnc(), "无密钥端点也要能建（本地 ollama 就是这个形态）");
    }

    @Test
    void emptyConfigSeedsNothing() {
        migration.run(null);

        assertTrue(saved.isEmpty(), "什么都不配时不能静默造一个 mock 端点——索引应保持 disabled 降级");
    }

    @Test
    void partialConfigSeedsNothing() {
        seed.setBaseUrl("https://api.openai.com/v1"); // 只有 baseUrl，没有 model

        migration.run(null);

        assertTrue(saved.isEmpty());
    }

    @Test
    void doesNotReseedWhenEndpointsAlreadyExist() {
        when(repo.countByKind(ModelEndpointEntity.KIND_EMBEDDING)).thenReturn(1L);
        seed.setProvider("mock");

        migration.run(null);

        verify(repo, never()).save(any());
        assertTrue(saved.isEmpty(), "已有端点时重写会把用户在 UI 的修改打回配置默认值");
    }

    @Test
    void neverThrowsOutOfRun() {
        when(repo.countByKind(any())).thenThrow(new IllegalStateException("db down"));

        migration.run(null); // 不抛：迁移失败只降级，不能让整个服务起不来
    }

    @Test
    void savedEndpointIsCapturedInOrder() {
        seed.setProvider("mock");
        ArgumentCaptor<ModelEndpointEntity> captor = ArgumentCaptor.forClass(ModelEndpointEntity.class);

        migration.run(null);

        verify(repo).save(captor.capture());
        assertEquals(ModelEndpointEntity.KIND_EMBEDDING, captor.getValue().getKind());
    }
}
