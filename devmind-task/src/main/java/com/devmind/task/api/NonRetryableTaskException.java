package com.devmind.task.api;

/**
 * CAP-80 FR-04：handler 抛此异常表示业务拒绝类失败——直接终态不重试
 *（对齐 CAP-55 retryable 分类口径：业务拒绝类不重试，连接/超时/5xx 类可重试）。
 */
public class NonRetryableTaskException extends RuntimeException {

    public NonRetryableTaskException(String message) {
        super(message);
    }

    public NonRetryableTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
