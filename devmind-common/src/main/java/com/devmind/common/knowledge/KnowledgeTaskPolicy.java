package com.devmind.common.knowledge;
/** Task payloads cannot confer knowledge access; ownership and current resource rights are rechecked. */
public interface KnowledgeTaskPolicy {
    enum Use { READ, EXECUTE }
    void require(String actor,String taskOwner,String type,String payload,Use use);
}
