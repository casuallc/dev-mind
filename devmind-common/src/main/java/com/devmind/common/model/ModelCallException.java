package com.devmind.common.model;

/**
 * CAP-48 模型调用失败基类：embedding（FR-05，{@link EmbeddingCallException}）与
 * 对话（FR-11，{@code OpenAiCompatChat}）都归到它下面。
 *
 * <p>消息已脱敏（剔除形如 {@code sk-...} 与 {@code Bearer ...} 的片段），可安全落库与回显。</p>
 *
 * <p>分基类而不是共用一个类：调用方 catch 一个类型就能覆盖所有模型调用失败，
 * 而消息前缀仍能说清是哪种协议失败（{@code embedding 调用失败:} / {@code 对话调用失败:}）。</p>
 */
public class ModelCallException extends RuntimeException {

    public ModelCallException(String message) {
        super(message);
    }

    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
