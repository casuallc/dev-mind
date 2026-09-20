package com.devmind.knowledge.embedding;

import com.devmind.common.model.ModelEndpointProvider;
import com.devmind.common.model.ModelEndpointView;
import com.devmind.knowledge.config.KnowledgeProperties;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * CAP-48 FR-04 解析实现：<b>端点表是唯一事实源</b>。
 *
 * <p>两条路径，二选一，不叠加：</p>
 * <ol>
 *   <li>{@code devmind-model} 已装配 → 一律问它（库级覆盖 → 平台默认 → 无）。返回"无"就是无，
 *       <b>不回落</b> CAP-44 的全局配置——否则 UI 里把端点删了，索引还在偷偷用配置里的旧端点，
 *       用户看到的降级提示与实际行为不符。</li>
 *   <li>{@code devmind-model} 未装配（模块裁剪/回滚到 CAP-44）→ 退回
 *       {@code devmind.knowledge.embedding.*} 装配的全局客户端，存量部署不至于因裁剪模块而失效。</li>
 * </ol>
 *
 * <p>端点记录里的 topK/threshold 优先于平台配置：换模型后合理阈值差异极大
 * （不同模型的余弦分布完全不同），端点级覆盖是必要的。</p>
 */
@Component
public class KnowledgeEndpointResolver implements EmbeddingResolver {

    private final ObjectProvider<ModelEndpointProvider> endpointProviders;
    private final EmbeddingClient legacyClient;
    private final KnowledgeProperties props;

    public KnowledgeEndpointResolver(ObjectProvider<ModelEndpointProvider> endpointProviders,
                                     EmbeddingClient legacyClient,
                                     KnowledgeProperties props) {
        this.endpointProviders = endpointProviders;
        this.legacyClient = legacyClient;
        this.props = props;
    }

    @Override
    public Resolution resolve(Long kbEndpointId) {
        ModelEndpointProvider provider = endpointProviders.getIfAvailable();
        if (provider != null) {
            Optional<ModelEndpointView> endpoint = provider.resolve(kbEndpointId);
            return endpoint.map(this::fromEndpoint).orElseGet(Resolution::unavailable);
        }
        KnowledgeProperties.Embedding cfg = props.getEmbedding();
        if (legacyClient.available()) {
            return new Resolution(null, legacyClient.model(), null, cfg.getThreshold(), cfg.getTopK(),
                    legacyClient);
        }
        return Resolution.unavailable();
    }

    @Override
    public boolean anyConfigured() {
        return resolve(null).available();
    }

    private Resolution fromEndpoint(ModelEndpointView e) {
        KnowledgeProperties.Embedding cfg = props.getEmbedding();
        // mock 无远端可探：维度取端点记录（连接测试/迁移写入），未探测过则回落配置默认
        EmbeddingClient client = e.mock()
                ? new MockEmbeddingClient(e.dimensions() == null ? cfg.getDimensions() : e.dimensions())
                : new OpenAiCompatEmbeddingClient(e.baseUrl(), e.apiKey(), e.model(),
                        e.timeoutSeconds(), e.batchSize());
        return new Resolution(e.id(), client.model(), e.dimensions(),
                e.threshold() == null ? cfg.getThreshold() : e.threshold(),
                e.topK() == null ? cfg.getTopK() : e.topK(),
                client);
    }
}
