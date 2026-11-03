package com.devmind.common.authz;

/**
 * CAP-108 操作副作用等级。GENERATE 消耗模型预算但不写业务对象；WRITE/DANGEROUS 对 AI 来源强制确认凭据。
 */
public enum OperationEffect {
    READ,
    GENERATE,
    WRITE,
    DANGEROUS
}
