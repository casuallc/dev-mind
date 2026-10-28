package com.devmind.knowledge.model;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="knowledge_audit",indexes=@Index(columnList="actor,created_at"))
public class KnowledgeAuditEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String actor;
    private String action;
    private String resourceType;
    private Long resourceId;
    @Column(length=1000) private String reason;
    private String queryDigest;
    private Long durationMs;
    private Integer candidates;
    private Instant createdAt;
    private String traceId;
    public static KnowledgeAuditEntity of(String actor,String action,String type,Long id,String reason) {
        var e=new KnowledgeAuditEntity();e.actor=actor;e.action=action;e.resourceType=type;e.resourceId=id;
        e.reason=reason;e.createdAt=Instant.now();e.traceId=java.util.UUID.randomUUID().toString();return e;
    }
    public KnowledgeAuditEntity metrics(String digest,long elapsed,int candidates){queryDigest=digest;durationMs=elapsed;this.candidates=candidates;return this;}
    public Long getId(){return id;}public String getActor(){return actor;}public String getAction(){return action;}
    public String getResourceType(){return resourceType;}public Long getResourceId(){return resourceId;}
    public String getReason(){return reason;}public String getQueryDigest(){return queryDigest;}public Long getDurationMs(){return durationMs;}
    public Integer getCandidates(){return candidates;}public Instant getCreatedAt(){return createdAt;}public String getTraceId(){return traceId;}
}
