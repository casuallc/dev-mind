package com.devmind.knowledge.embedding;

import com.devmind.knowledge.config.KnowledgeProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 devmind.knowledge.embedding.* 装配 EmbeddingClient：
 * provider=mock → MockEmbeddingClient；baseUrl+model 齐备 → OpenAiCompatEmbeddingClient；
 * 否则不可用实现（索引标 disabled、检索降级 LIKE）。
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingClient embeddingClient(KnowledgeProperties props) {
        KnowledgeProperties.Embedding e = props.getEmbedding();
        if ("mock".equalsIgnoreCase(nullToEmpty(e.getProvider()))) {
            return new MockEmbeddingClient(e.getDimensions());
        }
        if (!nullToEmpty(e.getBaseUrl()).isBlank() && !nullToEmpty(e.getModel()).isBlank()) {
            return new OpenAiCompatEmbeddingClient(e.getBaseUrl(), e.getApiKey(), e.getModel(),
                    e.getTimeoutSeconds());
        }
        return new UnavailableEmbeddingClient();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 未配置 embedding：available()=false，embed 直接抛错（正常链路不会调到） */
    static final class UnavailableEmbeddingClient implements EmbeddingClient {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String model() {
            return "";
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            throw new EmbeddingException("embedding 未配置（devmind.knowledge.embedding.*）");
        }
    }
}
