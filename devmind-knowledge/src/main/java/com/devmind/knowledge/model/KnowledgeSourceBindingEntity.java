package com.devmind.knowledge.model;
import jakarta.persistence.*;
@Entity @Table(name="knowledge_source_bindings")
public class KnowledgeSourceBindingEntity {
    @Id private Long entryId;
    private String mode="LIVE";
    private int maxAgeSeconds=60;
    private String approvedBy;
    private boolean revoked;
    @Version private long revision;
    public Long getEntryId(){return entryId;}public void setEntryId(Long v){entryId=v;}
    public String getMode(){return mode;}public void setMode(String v){mode=v;}
    public int getMaxAgeSeconds(){return maxAgeSeconds;}public void setMaxAgeSeconds(int v){maxAgeSeconds=v;}
    public String getApprovedBy(){return approvedBy;}public void setApprovedBy(String v){approvedBy=v;}
    public boolean isRevoked(){return revoked;}public void setRevoked(boolean v){revoked=v;}
    public long getRevision(){return revision;}
}
