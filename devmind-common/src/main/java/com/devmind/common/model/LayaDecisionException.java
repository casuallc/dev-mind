package com.devmind.common.model;

/**
 * CAP-55 决策边车调用失败，带 HTTP 状态码——存在的理由是让调用方能<b>分辨"值不值得重试"</b>。
 *
 * <p>{@link ModelCallException} 只说"失败了"，而 FR-03 的决策引擎要按"失败一次重试"处理：
 * 连不上（边车正在重启）、读超时（负载抖动）、5xx（边车内部临时故障）重试一次是有意义的；
 * 而 4xx（未知 checkpoint 名 / schema 非法）与应答解析失败重试多少次都是同一个结果，
 * 只会把 120ms 的往返拖成两次。靠异常消息里找"400"来分辨是脆的，故单独立类型。</p>
 *
 * <p>与 {@link EmbeddingCallException} 同样是 {@link ModelCallException} 的子类：调用方
 * catch 父类仍能一网打尽（FR-02 的连接测试就是这么用的，它有自己的不重试口径）。</p>
 */
public class LayaDecisionException extends ModelCallException {

    /** 没有 HTTP 响应时的状态：连不上 / 超时 / 被中断 */
    private static final int NO_RESPONSE = 0;

    private final int status;

    private LayaDecisionException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** 边车回了但状态码非 2xx */
    public static LayaDecisionException status(int status, String message) {
        return new LayaDecisionException(message, status, null);
    }

    /** 压根没拿到响应（连接被拒/超时/流中断），{@code cause} 是底层 IO 异常 */
    public static LayaDecisionException transport(String message, Throwable cause) {
        return new LayaDecisionException(message, NO_RESPONSE, cause);
    }

    /** 非 2xx 时的状态码；{@code 0} = 没有 HTTP 响应（连不上/超时） */
    public int status() {
        return status;
    }

    /**
     * 是否属于"看着像抖"的失败：5xx 或没有响应（连接被拒/读超时）。
     *
     * <p>4xx 与"压根没连上"里被<b>主动中断</b>的那类（{@code InterruptedException}）都不算——
     * 前者重试无用，后者是有人在喊停，重试等于顶着停机信号再打一次。</p>
     */
    public boolean retryable() {
        if (status != NO_RESPONSE) {
            return status >= 500;
        }
        for (Throwable t = getCause(); t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }
}
