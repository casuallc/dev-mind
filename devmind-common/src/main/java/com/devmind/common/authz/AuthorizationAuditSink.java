package com.devmind.common.authz;

/**
 * CAP-108 FR-07 授权审计 SPI（auth 实现）：全入口最终 ALLOW/DENY/ERROR 落库。
 * 实现必须独立于业务事务提交（拒绝记录不随业务回滚丢失）。
 */
public interface AuthorizationAuditSink {

    void record(AuthorizationAuditEvent event);
}
