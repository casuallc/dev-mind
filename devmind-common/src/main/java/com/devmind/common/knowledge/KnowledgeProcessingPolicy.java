package com.devmind.common.knowledge;
import java.util.Collection;
/** Model processing permission is independent of human READ permission. */
public interface KnowledgeProcessingPolicy {
    enum Purpose { GENERATION, EMBEDDING, REWRITE, ENRICH, RERANK, OCR }
    void requireProcessing(String actor, Collection<Long> kbIds, Collection<Long> entryIds, Long endpointId, Purpose purpose);
}
