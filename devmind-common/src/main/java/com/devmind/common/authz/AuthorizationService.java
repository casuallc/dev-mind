package com.devmind.common.authz;

/**
 * CAP-108 核心授权 SPI（auth 实现）：应用 Service 公开用例方法的最终检查。
 * 只使用服务端可信上下文；不接受客户端传入的授权结论。
 */
public interface AuthorizationService {

    /**
     * 最终授权检查：拒绝抛 {@link AuthorizationException}（不可见 404 / 无权 403 / 策略不可用 503 / 未认证 401）。
     * 必须发生在业务写入、外部派发、读取敏感正文之前。
     */
    void require(String operationId, ResourceTarget target);

    /** 判定不抛异常（预检/影子比对用），不替代执行边界的 {@link #require}。 */
    AuthorizationDecision check(String operationId, ResourceTarget target);
}
