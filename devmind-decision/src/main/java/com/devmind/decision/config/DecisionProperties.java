package com.devmind.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAP-55 devmind.decision.* — 决策引擎（laya 边车调用）配置。
 *
 * <p>端点与凭据不在这里：那是 CAP-48 的 {@code model_endpoints}（kind=DECISION）+ 平台默认端点，
 * 享受同一套密文凭据/连接测试/唯一默认。这里只管"怎么调"这一层。</p>
 */
@ConfigurationProperties(prefix = "devmind.decision")
public class DecisionProperties {

    /**
     * 单次决策调用超时（秒）。默认 3：常驻边车单次 33ms 上下，3 秒已经是两个数量级的余量，
     * 再长只会让"边车挂了"变成用户盯着转圈。
     */
    private int timeoutSeconds = 3;

    /**
     * 传输类失败的额外重试次数（默认 1）。只对"看着像抖"的失败重试（连不上/读超时/5xx），
     * 4xx 与应答解析失败不重试——详见 {@code LayaDecisionException#retryable()}。
     */
    private int retryCount = 1;

    /**
     * 重试前的固定等待（毫秒，默认 200）。不引退避算法：决策是同步等待的交互路径，
     * 200ms 已够让一次瞬时抖动（连接被拒/丢包）过去，再长还不如直接降级让用户走人工。
     */
    private long retryBackoffMillis = 200;

    public int getTimeoutSeconds() { return timeoutSeconds; }

    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public int getRetryCount() { return retryCount; }

    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public long getRetryBackoffMillis() { return retryBackoffMillis; }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }
}
