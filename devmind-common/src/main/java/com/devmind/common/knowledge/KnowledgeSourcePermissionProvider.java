package com.devmind.common.knowledge;
import java.time.Instant;
/** Connector-owned authorization using mapped user/delegated credentials, never the ingestion service token alone. */
public interface KnowledgeSourcePermissionProvider {
    String source();
    record Decision(String actor,boolean allowed,Instant verifiedAt,Instant expiresAt,String sourceRevision,String mappingRevision) { }
    Decision verify(String actor,String externalId);
}
