package com.devmind.common.knowledge;
/** Cross-module gate. Assets explicitly marked derived must fail closed when this SPI is missing. */
public interface KnowledgeDerivedPolicy {
    void requireRead(String actor,String resourceType,Long resourceId);
    default void requireMaterialize(String actor,String resourceType,Long resourceId,String sessionId) {
        throw new com.devmind.common.exception.DevMindException(com.devmind.common.exception.ErrorCode.CONFLICT,
                "当前执行节点尚未验证受限来源的隔离和租约清理能力，请使用服务端知识问答");
    }
}
