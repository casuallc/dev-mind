package com.devmind.knowledge.embedding;

import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.knowledge.config.KnowledgeProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CAP-48 FR-11 守卫：端点表不再只有向量端点，解析链必须按 kind 收口——
 * 一个 CHAT 端点（无论是库级绑定还是平台默认）都不能被当成向量端点用。
 */
class KnowledgeEndpointResolverTest {

    private ModelEndpointProvider provider;
    private EmbeddingClient legacyClient;
    private KnowledgeEndpointResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = mock(ModelEndpointProvider.class);
        legacyClient = mock(EmbeddingClient.class);
        lenient().when(legacyClient.available()).thenReturn(false);
        ObjectProvider<ModelEndpointProvider> providers = mock(ObjectProvider.class);
        lenient().when(providers.getIfAvailable()).thenReturn(provider);
        resolver = new KnowledgeEndpointResolver(providers, legacyClient, new KnowledgeProperties());
    }

    private static ModelEndpointView view(long id, String kind, String model, Integer dimensions) {
        return new ModelEndpointView(id, kind, ModelEndpointView.PROVIDER_OPENAI, "端点" + id,
                "https://api.example.com/v1", null, model, dimensions, 30, 32, null, null);
    }

    @Test
    void chatEndpointIsNeverUsedAsVectorEndpoint() {
        when(provider.resolve(any())).thenReturn(Optional.of(view(7L, "CHAT", "gpt-4o-mini", null)));

        EmbeddingResolver.Resolution r = resolver.resolve(7L);

        assertFalse(r.available(), "对话端点不得被当成向量端点（会拿对话模型名打 /embeddings）");
        assertNull(r.endpointId());
        assertFalse(resolver.anyConfigured(), "只有对话端点 ≠ 有可用的向量端点");
    }

    @Test
    void embeddingEndpointStillResolves() {
        when(provider.resolve(any())).thenReturn(Optional.of(view(9L, "EMBEDDING", "bge-m3", 1024)));

        EmbeddingResolver.Resolution r = resolver.resolve(9L);

        assertTrue(r.available());
        assertEquals(9L, r.endpointId());
        assertEquals("bge-m3", r.model());
        assertEquals(1024, r.dimensions());
        assertNotNull(r.client());
        assertTrue(resolver.anyConfigured());
    }
}
