package com.devmind.knowledge.embedding;

/**
 * embedding 远程调用失败（网络/非 2xx/响应结构异常）。
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
