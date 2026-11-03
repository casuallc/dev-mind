package com.devmind.common.authz;

/**
 * CAP-108 §4.2.3 资源策略 SPI（各业务模块实现，app 组装）：
 * 反查真实对象归属，返回单对象授权事实；只返回授权所需最小事实。
 * 实现必须自行处理 ADMIN/SYSTEM 语义——引擎不替策略绕过资源检查
 * （如 OWNER_ONLY 不因 ADMIN 扩展写入对象）。
 */
public interface ResourcePolicy {

    /** 策略标识，OperationSpec.resourcePolicyId 引用；启动校验唯一且存在。 */
    String policyId();

    /**
     * @return ALLOW / DENY（不可见对象用 {@link ReasonCodes#RESOURCE_HIDDEN}，对外 404）；
     *         不得返回 ERROR——策略不可用应抛出异常，由引擎归一为 ERROR 决策
     */
    AuthorizationDecision evaluate(ResolvedSubject subject, OperationSpec operation, ResourceTarget target);
}
