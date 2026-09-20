package com.devmind.common.model;

/**
 * CAP-48 OpenAI 兼容向量调用失败（网络/非 2xx/响应结构异常/维度不一致）。
 * 消息已脱敏（剔除形如 {@code sk-...} 与 {@code Bearer ...} 的片段），可安全落库与回显。
 *
 * <p>父类为 {@link ModelCallException}（FR-11 起）：既有 {@code catch (EmbeddingCallException)} 全部照旧生效，
 * 新写的模型侧调用方 catch 父类即可同时覆盖向量与对话两条链。</p>
 */
public class EmbeddingCallException extends ModelCallException {

    public EmbeddingCallException(String message) {
        super(message);
    }

    public EmbeddingCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
