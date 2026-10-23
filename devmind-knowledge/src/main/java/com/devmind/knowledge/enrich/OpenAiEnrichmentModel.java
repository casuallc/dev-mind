package com.devmind.knowledge.enrich;

import com.devmind.common.model.ModelEndpointView;
import com.devmind.common.model.OpenAiCompatChat;
import org.springframework.stereotype.Component;

/** OpenAI 兼容 {@code /chat/completions} 实现；要求端点已由调用方按 CHAT 过滤。 */
@Component
public class OpenAiEnrichmentModel implements EnrichmentModel {

    @Override
    public String complete(ModelEndpointView endpoint, String prompt, int timeoutSeconds) {
        if (endpoint == null || !endpoint.chat()) {
            throw new IllegalArgumentException("自动增强需要 CHAT 端点");
        }
        if (endpoint.baseUrl() == null || endpoint.baseUrl().isBlank()
                || endpoint.model() == null || endpoint.model().isBlank()) {
            throw new IllegalStateException("CHAT 端点「" + endpoint.display() + "」缺少 baseUrl 或模型名");
        }
        return OpenAiCompatChat.complete(new OpenAiCompatChat.Options(
                endpoint.baseUrl(), endpoint.apiKey(), endpoint.model(), timeoutSeconds), prompt);
    }
}
