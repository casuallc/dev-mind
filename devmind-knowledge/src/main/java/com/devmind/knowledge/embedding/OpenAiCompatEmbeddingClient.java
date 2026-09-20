package com.devmind.knowledge.embedding;

import com.devmind.common.model.EmbeddingCallException;
import com.devmind.common.model.OpenAiCompatEmbeddings;
import java.util.List;

/**
 * OpenAI 兼容 /embeddings 端点客户端（vLLM/OneAPI/Ollama 等同协议服务通用）。
 *
 * <p>CAP-48 起真正的调用逻辑（分批、重试、响应校验、错误脱敏）在 common 的
 * {@link OpenAiCompatEmbeddings}——devmind-model 的连接测试也调它，两处不能各写一份
 * （尤其是"报错时不得回显 apiKey"这条）。本类只做 {@link EmbeddingClient} 契约适配。</p>
 */
public class OpenAiCompatEmbeddingClient implements EmbeddingClient {

    private final OpenAiCompatEmbeddings.Options options;

    /** CAP-44 兼容构造：不分批（一次带完），超时 30s */
    public OpenAiCompatEmbeddingClient(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this(baseUrl, apiKey, model, timeoutSeconds, 32);
    }

    public OpenAiCompatEmbeddingClient(String baseUrl, String apiKey, String model, int timeoutSeconds,
                                       int batchSize) {
        this.options = new OpenAiCompatEmbeddings.Options(baseUrl, apiKey, model, timeoutSeconds,
                Math.max(1, batchSize));
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return options.model();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        try {
            return OpenAiCompatEmbeddings.embed(options, texts);
        } catch (EmbeddingCallException e) {
            // 保持 CAP-44 的契约：远程调用失败抛 EmbeddingException，调用方据此落 index_status=failed
            throw new EmbeddingException(e.getMessage(), e);
        }
    }
}
